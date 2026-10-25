# Deployment Strategy — Order Service

> Phạm vi: release v1.4 (payment status model mới) của `order-service` trong flash sale platform.
> Manifest tham chiếu: [`k8s/`](./k8s), migration chi tiết: [`migration-plan.md`](./migration-plan.md).

## 1. Image strategy

**Image naming/tagging:**
- `registry.example.com/flashsale/order-service:<semver>-<git-sha>` — ví dụ `1.4.0-a1b2c3d`.
- Tag **bất biến** (immutable tag trên registry); không deploy `latest`. Manifest production pin thêm digest `@sha256:...` do CI xuất ra.
- Một image build **một lần** ở CI, promote qua dev → staging → prod (không rebuild theo môi trường; khác biệt môi trường chỉ nằm ở config).
- OCI labels: `version`, `revision` (git sha) để truy ngược pod → commit.

**Build method:**
- Dockerfile multi-stage ([`Dockerfile`](./Dockerfile)): stage `build` (Gradle + JDK 17) → stage `runtime` (JRE 17). JAR tách layer bằng `layertools` để cache dependency.
- Phương án thay thế: `./gradlew bootBuildImage --imageName=...` (Cloud Native Buildpacks) — chuẩn hoá, mặc định non-root, phù hợp khi không cần kiểm soát chi tiết.
- CI: unit/contract test → build image → scan (Trivy/Grype, fail nếu CRITICAL) → sinh SBOM → ký image (cosign) → push.

**Base image:**
- Build: `gradle:8.7.0-jdk17`; Runtime: `eclipse-temurin:17.0.11_9-jre-alpine` (pin version, cập nhật định kỳ qua Renovate/Dependabot khi có CVE patch).
- Runtime không chứa source code, Gradle, JDK, `.git`, `.env`.

**Non-root policy:**
- Image: `USER 10001:10001`.
- Pod: `runAsNonRoot: true`, `runAsUser: 10001`, `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop: [ALL]`, `seccompProfile: RuntimeDefault`. `/tmp` là `emptyDir`.
- Namespace bật Pod Security Admission mức `restricted`.

## 2. Runtime configuration

**ConfigMap fields** ([`order-service-config.yaml`](./k8s/order-service-config.yaml)):
`SPRING_PROFILES_ACTIVE`, `APP_ENV`, `SERVER_PORT`, `SHUTDOWN_TIMEOUT`, `LOGGING_LEVEL_*`, `DB_URL` (host/db name, không credential), `DB_POOL_*`, `JPA_DDL_AUTO=validate`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, Kafka security protocol/mechanism, `KAFKA_TOPIC_ORDER_EVENTS`, `INVENTORY_SERVICE_URL`, `PAYMENT_SERVICE_URL`, timeouts, feature flags `FEATURE_STATUS_V2_WRITE` / `FEATURE_STATUS_V2_READ`, cấu hình Actuator.

**Secret fields** ([`order-service-secret.example.yaml`](./k8s/order-service-secret.example.yaml) — template):
`DB_USERNAME`, `DB_PASSWORD`, `SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG` (Kafka credential), `PAYMENT_API_KEY`, `JWT_PUBLIC_KEY_PEM`/signing key.
Nguồn thật: Vault / cloud secret manager → External Secrets Operator sinh K8s Secret; etcd encryption-at-rest; RBAC chỉ cho CD pipeline đọc.

**Fields not allowed in Git:**
- Mọi giá trị thật của: DB password, Kafka username/password/JAAS, API key, private key/JWT signing key, TLS key/keystore, token registry.
- File `.env` thật, `application-secret*.yml`, dump DB, log chứa PII.
- Kiểm soát: `.gitignore` + `.dockerignore`, pre-commit `gitleaks`, secret scanning trên CI/repo. Nếu lỡ commit → **rotate ngay** (xoá history là chưa đủ).

## 3. Health checks

| Probe | Endpoint | Gồm health contributor | Hành động khi fail |
|---|---|---|---|
| Startup | `/actuator/health/liveness` | `livenessState` | Đợi tối đa 150s (30×5s) rồi mới restart; chặn liveness/readiness chạy sớm |
| Liveness | `/actuator/health/liveness` | `livenessState`, `ping` | Restart container |
| Readiness | `/actuator/health/readiness` | `readinessState`, `db`, (`outboxBacklog`) | Gỡ pod khỏi Service endpoints, không restart |

**Liveness endpoint:** `/actuator/health/liveness` — chỉ trạng thái nội tại của JVM/app (`LivenessState.CORRECT/BROKEN`).

**Readiness endpoint:** `/actuator/health/readiness` — `readinessState` (tự chuyển `REFUSING_TRAFFIC` khi shutdown) + `db`.

**Why external dependencies are excluded/included:**
- **Loại khỏi liveness:** DB, Kafka, Inventory, Payment. Dependency ngoài lỗi ≠ process Order Service chết. Nếu liveness check DB, khi DB chập chờn kubelet restart **toàn bộ** pod cùng lúc → cold start, mất connection pool, thundering herd lên DB khi phục hồi → cascading failure.
- **DB có trong readiness:** không có DB thì `POST /orders` và `GET /orders/{id}` đều không phục vụ được → không nên nhận traffic. Nhưng: nếu DB chết thì *tất cả* pod NotReady cùng lúc → Gateway trả 503 nhanh (fail-fast) thay vì timeout — chấp nhận được và có alert riêng.
- **Kafka KHÔNG có trong readiness:** dùng outbox pattern → order vẫn ghi DB, event publish sau. Kafka down chỉ làm tăng outbox backlog (alert theo metric), không cần chặn traffic. `outboxBacklog` chỉ fail readiness khi backlog vượt ngưỡng rất cao (bảo vệ DB), không fail khi Kafka chớp nhoáng.
- **Inventory/Payment KHÔNG có trong readiness:** đã có timeout + circuit breaker + fallback (day-2). Nếu đưa vào readiness, Inventory down → mọi Order pod NotReady → mất luôn cả `GET /orders` dù không cần Inventory → outage lan toàn platform.
- Graceful shutdown: `preStop sleep 10s` → readiness `REFUSING_TRAFFIC` → `server.shutdown=graceful` (30s) → `terminationGracePeriodSeconds: 45`.

## 4. Rollout strategy

**Rolling / Canary:**
- Thay đổi thường (không đổi hành vi nghiệp vụ): **RollingUpdate** `maxSurge: 1`, `maxUnavailable: 0`, `minReadySeconds: 20` → không giảm capacity, pod chỉ nhận traffic khi readiness pass.
- Release v1.4 (status model mới, ảnh hưởng tiền): **Canary**.
  - Cách 1 (K8s thuần): Deployment `order-service-canary` (label `track: canary`, cùng `app: order-service`) → Service chia traffic theo tỉ lệ replica (1 canary / 9 stable ≈ 10%).
  - Cách 2 (khuyến nghị): Argo Rollouts / Flagger + Istio/Gateway API → chia theo **% traffic chính xác**, tự động analysis & abort.
- Kết hợp **feature flag** `FEATURE_STATUS_V2_READ`: deploy code trước (flag off), bật flag theo canary → tách "deploy" khỏi "release".
- Không deploy trong khung giờ flash sale; có freeze window trước sự kiện.

**Canary percentage & Duration:**

| Bước | Traffic canary | Thời gian quan sát tối thiểu | Điều kiện qua bước |
|---|---|---|---|
| 0 | 0% (pod canary Ready, smoke test nội bộ, synthetic order) | 10 phút | Smoke pass |
| 1 | 10% | 30 phút | Không chạm rollback criteria |
| 2 | 25% | 30 phút | như trên |
| 3 | 50% | 1 giờ | như trên + so sánh payment reconciliation |
| 4 | 100% | theo dõi tăng cường 24h | — |

Phải đủ **lượng request tối thiểu** (vd ≥ 2.000 checkout ở canary) trước khi kết luận, không chỉ dựa vào thời gian.

**Metrics monitored** (so sánh **canary vs stable** cùng thời điểm, không so với hôm qua):
- HTTP: 5xx rate & p95/p99 latency của `POST /orders`, `GET /orders/{id}` (label `version`).
- Business: checkout success rate, order `PAYMENT_PROCESSING` > 10 phút (stuck), `PAYMENT_FAILED` rate, payment mismatch (Order vs Payment Service), `status mismatch` giữa `status` và `status_v2`, `order_status_unknown_total`.
- Messaging: outbox backlog, consumer lag, DLQ rate.
- Runtime: pod restart count, readiness failure, JVM heap/GC pause, Hikari pending connections, DB CPU/lock wait.

## 5. Rollback criteria

Rollback canary **tự động/ngay lập tức** nếu bất kỳ điều kiện nào đúng:

| Metric | Ngưỡng |
|---|---|
| Checkout 5xx rate (canary) | > 1% trong 5 phút, **hoặc** > 2× stable |
| p95 `POST /orders` | > 800 ms trong 10 phút |
| Order `PAYMENT_PROCESSING` stuck > 10 phút | tăng > 3× baseline, hoặc > 50 order |
| Payment mismatch (Order ≠ Payment) | > 0.1% hoặc bất kỳ trường hợp double-charge |
| `status` vs `status_v2` mismatch | > 0 (sau khi loại trừ row đang ghi) |
| Readiness failure | ≥ 2 pod NotReady > 2 phút |
| Pod restart | bất kỳ CrashLoopBackOff ở canary |
| Outbox backlog / consumer lag | tăng liên tục 15 phút sau release |
| DLQ | > 10 message/5 phút liên quan event status mới |

Trước khi kết luận "release mới gây incident": kiểm tra stable có cùng triệu chứng không (nếu có → khả năng do dependency/hạ tầng), dependency dashboard (DB, Kafka, Payment), deploy/config change khác cùng thời điểm.

## 6. Database migration

(Chi tiết SQL: [`migration-plan.md`](./migration-plan.md))

**Expand step:** R1 — `ADD COLUMN status_v2 VARCHAR(32) NULL`, CHECK `NOT VALID`, index `CONCURRENTLY`. Không xoá/đổi tên/đổi type gì. Chạy bằng Flyway Job trước deploy, `lock_timeout=3s`, app để `ddl-auto=validate`.

**Compatibility step:** R2 — app v1.4 dual-write `status` (map về giá trị legacy) + `status_v2`; tolerant reader; event thêm field additive `statusV2`, giữ `status`.

**Backfill:** sau khi 100% pod dual-write: batch 5.000 row, `FOR UPDATE SKIP LOCKED`, throttle, idempotent; verify `count(status_v2 IS NULL)=0`, phân bố cặp `(status, status_v2)`, `VALIDATE CONSTRAINT`.

**Cutover:** R3 — bật `FEATURE_STATUS_V2_READ` theo canary; đọc `status_v2` (fallback legacy); vẫn dual-write để giữ khả năng rollback.

**Contract/remove step:** R4a — xoá logic đọc legacy + flag (vẫn ghi legacy 1 release); R4b — sau rollback window ≥ 2 tuần + data verification + backup, `DROP COLUMN status`. Event bỏ field `status` chỉ khi mọi consumer đã chuyển.

## 7. Rollback plan

**Application rollback:**
- `kubectl rollout undo deployment/order-service` (hoặc Argo Rollouts `abort`) — ReplicaSet cũ còn giữ (`revisionHistoryLimit: 5`), image cũ còn trên registry.
- Canary: scale `order-service-canary` về 0 → 100% traffic về stable trong vài giây.
- An toàn vì ở R1–R3 schema chỉ additive và cột legacy luôn được dual-write → v1.3 đọc được dữ liệu do v1.4 ghi. **Không** rollback migration bằng cách drop cột mới.

**Feature-flag rollback (ưu tiên khi lỗi ở logic nghiệp vụ mới):**
- Đặt `FEATURE_STATUS_V2_READ=false` → quay về đọc legacy, giữ nguyên image (nhanh, không restart nếu flag đọc từ config động; với env var cần `rollout restart`).
- Thứ tự xử lý khi canary làm payment mismatch tăng: **(1) tắt flag ngay** để chặn ghi dữ liệu sai thêm → **(2) nếu vẫn lỗi** (bug ngoài vùng flag, crash, latency) thì rollback app → **(3) data repair**.

**Data repair:**
- Order kẹt `PAYMENT_PROCESSING`: job reconcile hỏi Payment Service theo `paymentId` → chuyển `CONFIRMED` / `PAYMENT_FAILED`; idempotent, chạy dry-run trước, log từng order.
- Row ghi bởi v1.3 trong lúc rollback (`status_v2 IS NULL`): chạy lại backfill.
- Mismatch `status` vs `status_v2`: script sửa theo nguồn sự thật (Payment ledger), xuất báo cáo cho CS/Finance; event bù (compensating event) qua outbox nếu downstream đã nhận trạng thái sai.
- Mọi script repair: review 2 người, chạy trên staging, có `LIMIT`/batch, lưu bảng audit `order_status_repair_log`.

