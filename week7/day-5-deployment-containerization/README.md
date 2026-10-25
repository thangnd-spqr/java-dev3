# Week 7 — Day 5: Spring Cloud Deployment & Containerization

Đóng gói và chuẩn bị deploy `order-service` (Spring Boot 3.2, Java 17, PostgreSQL, Kafka, Actuator) theo hướng production-ready.

## Cấu trúc

```
day-5-deployment-containerization/
├── Dockerfile                         # multi-stage, non-root, layered JAR
├── .dockerignore                      # chặn secret/build output khỏi build context
├── docker-compose.yml                 # order-service + PostgreSQL + Kafka (KRaft)
├── .env.example                       # template env, KHÔNG chứa secret thật
├── k8s/
│   ├── order-service-deployment.yaml  # Deployment + PDB, 3 probes, securityContext
│   ├── order-service-service.yaml     # ClusterIP
│   ├── order-service-config.yaml      # ConfigMap (config không nhạy cảm)
│   └── order-service-secret.example.yaml  # Secret template (placeholder)
├── deployment-strategy.md             # image, config, health, rollout, rollback
├── migration-plan.md                  # expand/contract cho order status mới
└── README.md
```

Source code service: [`../../order-service`](../../order-service).

## Chạy local

```powershell
cd week7/day-5-deployment-containerization
Copy-Item .env.example .env          # rồi điền DB_PASSWORD
# Build context là ../../order-service → Docker đọc .dockerignore ở đó
Copy-Item .dockerignore ../../order-service/.dockerignore
docker compose up -d --build
docker compose ps                    # order-service phải (healthy)

curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
curl -X POST http://localhost:8080/orders -H "Content-Type: application/json" -d '{...}'
curl http://localhost:8080/orders/{id}

docker compose down -v
```

Build image riêng (CI):

```powershell
docker build -f week7/day-5-deployment-containerization/Dockerfile `
  --build-arg APP_VERSION=1.4.0 --build-arg GIT_SHA=a1b2c3d `
  -t registry.example.com/flashsale/order-service:1.4.0-a1b2c3d order-service
# Hoặc Buildpacks: cd order-service; gradle bootBuildImage --imageName=order-service:1.4.0-a1b2c3d
```

Kiểm tra non-root & không có secret trong image:

```powershell
docker run --rm --entrypoint id order-service:1.4.0-local          # uid=10001(app)
docker history --no-trunc order-service:1.4.0-local | Select-String -Pattern "PASSWORD|SECRET|TOKEN"   # phải rỗng
```

> [!NOTE]
> `order-service` hiện chưa có dependency `spring-kafka`; compose đã cấp `SPRING_KAFKA_BOOTSTRAP_SERVERS` để khi thêm `implementation 'org.springframework.kafka:spring-kafka'` (outbox publisher) thì chạy được ngay không cần đổi config.

## Deploy Kubernetes (mẫu)

```bash
kubectl apply -f k8s/order-service-config.yaml
# Secret: tạo từ secret manager / External Secrets — KHÔNG apply file example lên prod
kubectl apply -f k8s/order-service-deployment.yaml -f k8s/order-service-service.yaml
kubectl rollout status deployment/order-service
kubectl rollout undo deployment/order-service      # rollback app
```

Migration DB chạy bằng Flyway Job **trước** rollout, app dùng `JPA_DDL_AUTO=validate`.

---

## Trả lời câu hỏi tự review

**1. Docker container start được có đồng nghĩa service sẵn sàng nhận traffic không?**
Không. "Started" chỉ là process đã chạy; JVM còn khởi tạo Spring context, Hikari pool, migration check, warm-up. Container cũng có thể chạy nhưng DB chưa kết nối được. Chỉ khi **readiness = UP** mới nên nhận traffic. `depends_on` của Compose cũng chỉ đảm bảo thứ tự, không đảm bảo sẵn sàng.

**2. Liveness và readiness khác nhau thế nào?**
Liveness: "process có bị hỏng/treo đến mức phải restart không?" → fail thì kubelet **restart** container. Readiness: "lúc này có nên nhận request không?" → fail thì pod bị **gỡ khỏi endpoints**, không restart, tự quay lại khi pass. Startup probe: bảo vệ giai đoạn khởi động chậm, chặn liveness kill pod khi app chưa lên xong.

**3. Vì sao liveness không nên fail chỉ vì Kafka hoặc Payment Service down?**
Restart Order Service không sửa được Kafka/Payment. Mọi pod cùng thấy dependency lỗi → cùng fail liveness → bị restart đồng loạt → mất toàn bộ capacity (kể cả các API không cần Kafka/Payment), cold start, dồn connection lên DB khi phục hồi → cascading failure. Lỗi dependency được xử lý bằng timeout/circuit breaker/outbox, không bằng restart.

**4. Vì sao rollback image không đủ nếu migration đã drop column?**
Image và schema/data có vòng đời độc lập. Rollback image chỉ đổi code; cột và dữ liệu đã drop không quay lại. Image cũ vẫn query cột cũ → lỗi SQL → rollback thất bại. Dữ liệu ghi trong thời gian chạy bản mới cũng không có ở dạng cũ. Vì vậy chỉ drop ở bước contract, sau rollback window, khi không version nào còn cần.

**5. Khi rolling deployment, old/new version có thể cùng chạy bao lâu?**
Tối thiểu là suốt thời gian rollout (vài phút: số pod × (startup + `minReadySeconds`)), nhưng có thể kéo dài **nhiều giờ/ngày**: canary nhiều bước, rollout bị pause/stuck do readiness fail, rollback, cluster đa region rollout lệch nhau, consumer xử lý message cũ trong Kafka, client cache. Do đó phải thiết kế compatible **vô thời hạn** giữa N và N-1, không giả định "chỉ vài phút".

**6. ConfigMap và Secret khác nhau thế nào?**
ConfigMap: config không nhạy cảm (URL, port, timeout, flag), có thể commit Git, ai trong team cũng xem được. Secret: dữ liệu nhạy cảm (password, key, token), quyền truy cập RBAC riêng, có thể mã hoá at-rest trong etcd, mount qua tmpfs, tích hợp Vault/External Secrets. Lưu ý Secret mặc định chỉ base64 — không phải mã hoá — nên vẫn không được commit.

**7. Vì sao không được commit secret thật vào Git, kể cả private repository?**
Git lưu vĩnh viễn trong history và mọi bản clone/fork/CI cache/backup; xoá commit không thu hồi được. Private repo vẫn có nhiều người/tool có quyền đọc (nhân viên cũ, bot CI, tích hợp bên thứ ba), có thể bị lộ do token bị đánh cắp hay đổi visibility nhầm. Secret trong Git không rotate/audit/phân quyền theo môi trường được. Nếu lỡ commit → coi như đã lộ, phải rotate.

**8. Nếu readiness của toàn bộ Order Service pods fail vì Inventory Service down, business impact là gì?**
Service không còn endpoint → Gateway trả 503 cho **mọi** API Order: không đặt hàng, không xem đơn, không xem lịch sử, callback payment có thể thất bại (tiền đã trừ nhưng order không cập nhật). Một outage của Inventory bị khuếch đại thành outage toàn bộ checkout flow — đúng lúc flash sale thì mất doanh thu lớn. Đúng ra: Inventory down chỉ ảnh hưởng tạo đơn (circuit breaker fail-fast/queue), còn `GET /orders`, payment callback vẫn chạy.

**9. Nếu canary release làm payment mismatch tăng, cần rollback app hay disable feature flag trước?**
**Tắt feature flag trước** (hoặc dừng canary traffic về 0%) — nhanh nhất, chặn ngay việc ghi thêm dữ liệu sai, không phải chờ rollout. Nếu mismatch vẫn tăng (lỗi nằm ngoài vùng flag) → rollback app. Sau đó chạy reconcile/data repair cho các order đã bị ảnh hưởng — rollback không tự sửa dữ liệu đã sai.

**10. Metric nào cần xem trước khi kết luận deploy mới gây incident?**
So sánh **canary vs stable cùng thời điểm** (theo label `version`): 5xx rate, p95/p99 latency, checkout success rate, payment mismatch, stuck `PAYMENT_PROCESSING`, restart/readiness failure, outbox backlog/consumer lag/DLQ. Đồng thời loại trừ nguyên nhân khác: dependency dashboard (DB, Kafka, Payment), traffic spike, config/infra change cùng thời điểm, stable có cùng triệu chứng không. Nếu chỉ canary xấu → do release; nếu cả hai cùng xấu → nhiều khả năng do bên ngoài.

---

## Tiêu chí hoàn thành

- [x] Service build được thành Docker image — [`Dockerfile`](./Dockerfile) multi-stage (hoặc `bootBuildImage`).
- [x] Container chạy bằng non-root user — `USER 10001`, `runAsNonRoot`, `readOnlyRootFilesystem`.
- [x] Không có credential/secret thật trong source, image hoặc compose — `${DB_PASSWORD:?}` từ `.env`, Secret chỉ là template, `.dockerignore` chặn `.env`/key.
- [x] Chạy được local qua Docker Compose — [`docker-compose.yml`](./docker-compose.yml).
- [x] Có health endpoint qua Actuator — `/actuator/health/{liveness,readiness}` (cấu hình trong `order-service/src/main/resources/application.yml`).
- [x] Phân biệt đúng liveness, readiness và startup probe — [deployment-strategy.md §3](./deployment-strategy.md#3-health-checks).
- [x] Có Kubernetes manifest cơ bản — [`k8s/`](./k8s).
- [x] Có expand/contract migration plan — [`migration-plan.md`](./migration-plan.md).
- [x] Có canary/rollback criteria dựa trên metric — [deployment-strategy.md §4–5](./deployment-strategy.md#4-rollout-strategy).
- [x] Có giải thích vì sao release cần backward compatibility — [migration-plan.md §0](./migration-plan.md) & câu 4–5 ở trên.

