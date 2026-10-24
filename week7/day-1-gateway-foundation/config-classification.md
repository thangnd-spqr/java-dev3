# Config Classification – Externalized Configuration

## 1. Vì sao không hard-code config?

| Hard-code | Vấn đề |
|---|---|
| Service URL (`http://inventory-service-01:8080`) | Đổi host/port, scale, chuyển môi trường → phải sửa code, build lại |
| Timeout | Không tune được khi production chậm / có sự cố |
| API key | Lộ secret trong Git, ai có quyền đọc repo đều thấy; khó rotate |
| Database credential | Rủi ro bảo mật nghiêm trọng, vi phạm compliance (PCI-DSS, ISO 27001) |
| Kafka broker address | Mỗi môi trường (dev/staging/prod) một cluster khác nhau |
| Feature flag | Muốn bật/tắt tính năng phải deploy lại → mất khả năng rollback nhanh |

Nguyên tắc **12-Factor App – Config**: *"Lưu config trong môi trường"*. Cùng **một artifact** (jar/image) chạy được ở mọi môi trường, chỉ khác config.

```text
Build once ──► order-service:1.4.2 (image)
                 ├── dev      + config dev
                 ├── staging  + config staging
                 └── prod     + config prod + secrets từ Vault
```

---

## 2. Phân loại config

| Loại config | Ví dụ | Commit Git? | Lưu ở đâu |
|---|---|---|---|
| **Application config** | timeout, page size, route config, retry count, thread pool size | ✅ Có thể | `application.yml` trong repo, Config Server repo |
| **Environment config** | DB host dev/staging/prod, Kafka broker, service URL | ⚠️ Có thể, tùy chính sách | `application-{profile}.yml`, Config Server, ConfigMap, env var |
| **Secret** | DB password, API key, private key, JWT signing key, OAuth client secret | ❌ **Không** | Vault, AWS Secrets Manager, K8s Secret (+ encryption), `.env` local (gitignored) |
| **Feature flag** | `enable-new-payment-flow` | ✅ Có thể nhưng cần quản lý rõ | Config Server, Unleash, LaunchDarkly, Flagsmith |

### 2.1. Ví dụ cụ thể trong order-platform

| Key | Giá trị ví dụ | Loại | Commit? |
|---|---|---|---|
| `spring.cloud.gateway.routes[*].predicates` | `Path=/api/orders/**` | Application | ✅ |
| `clients.inventory.timeout` | `2s` | Application | ✅ |
| `order.page-size.default` | `20` | Application | ✅ |
| `resilience4j.retry.instances.inventory.max-attempts` | `3` | Application | ✅ |
| `INVENTORY_SERVICE_URL` | `http://inventory-service:8082` | Environment | ⚠️ |
| `spring.datasource.url` | `jdbc:postgresql://db-prod:5432/orders` | Environment | ⚠️ |
| `spring.kafka.bootstrap-servers` | `kafka-1:9092,kafka-2:9092` | Environment | ⚠️ |
| `CORS_ALLOWED_ORIGINS` | `https://shop.example.com` | Environment | ⚠️ |
| `spring.datasource.username` | `order_app` | Environment / Secret* | ⚠️ |
| `spring.datasource.password` | `******` | **Secret** | ❌ |
| `PAYMENT_PROVIDER_API_KEY` | `sk_live_...` | **Secret** | ❌ |
| `jwt.signing-key` / private key `.pem` | `-----BEGIN PRIVATE KEY-----` | **Secret** | ❌ |
| `spring.kafka.properties.sasl.jaas.config` | chứa password | **Secret** | ❌ |
| `features.enable-new-payment-flow` | `false` | Feature flag | ✅ (có owner, có ngày xoá) |

\* Username thường không coi là bí mật tuyệt đối, nhưng nhiều tổ chức quản lý chung với password trong Vault.

### 2.2. Cách nhận biết một config là **Secret**

Tự hỏi: *"Nếu giá trị này bị lộ ra Internet, kẻ tấn công có thể làm gì?"*
- Truy cập dữ liệu (DB password) → **Secret**
- Mạo danh hệ thống / tạo giao dịch (API key, client secret) → **Secret**
- Giả mạo token (JWT signing key, private key, certificate key) → **Secret**
- Biết timeout = 2s, page size = 20 → **không** phải secret.

---

## 3. Thứ tự ưu tiên config trong Spring Boot (cao → thấp, rút gọn)

1. Command line args (`--server.port=9090`)
2. `SPRING_APPLICATION_JSON`
3. OS environment variables (`INVENTORY_SERVICE_URL`)
4. Config import từ bên ngoài (Config Server, Vault, ConfigMap mount)
5. `application-{profile}.yml` ngoài jar → trong jar
6. `application.yml` ngoài jar → trong jar
7. `@PropertySource` / default values

→ Giá trị mặc định cho local nằm trong `application.yml`, môi trường thật override bằng env var / Config Server / Secret store.

---

## 4. Spring Cloud Config (Config Server / Config Client)

```text
           Git repo (config-repo)
           ├── application.yml            # dùng chung mọi service
           ├── order-service.yml
           ├── order-service-dev.yml
           └── order-service-prod.yml
                    │
                    ▼
            Config Server (:8888)  ◄── Vault backend cho secret (tuỳ chọn)
                    │
     ┌──────────────┼───────────────┐
     ▼              ▼               ▼
order-service  inventory-service  payment-service   (Config Client)
```

**Config Client** (`order-service/application.yml`):

```yaml
spring:
  application:
    name: order-service
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  config:
    import: "optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}"
```

**Config Server**:

```yaml
server:
  port: 8888
spring:
  cloud:
    config:
      server:
        git:
          uri: https://github.com/example/order-platform-config
          default-label: main
```

> Refresh config không cần restart: `@RefreshScope` + `POST /actuator/refresh` (hoặc Spring Cloud Bus để broadcast).
> Trên Kubernetes có thể thay Config Server bằng **ConfigMap + Secret** (mount làm file hoặc env var), hoặc dùng **Spring Cloud Kubernetes**.

---

## 5. Quản lý Secret đúng cách

| Môi trường | Cách làm |
|---|---|
| Local dev | File `.env` (đã thêm vào `.gitignore`), hoặc `application-local.yml` gitignored |
| Docker Compose | `env_file: .env` hoặc Docker secrets |
| Kubernetes | `Secret` (bật encryption at rest) + External Secrets Operator / Sealed Secrets |
| Cloud / Enterprise | HashiCorp Vault (`spring-cloud-starter-vault-config`), AWS Secrets Manager, Azure Key Vault, GCP Secret Manager |

Ví dụ import từ Vault:

```yaml
spring:
  config:
    import: "vault://secret/order-service"
```

Checklist:
- [x] Không commit secret; thêm `.env`, `*.pem`, `application-local.yml` vào `.gitignore`.
- [x] Bật secret scanning (GitHub secret scanning, gitleaks) trong CI.
- [x] Không log secret (mask trong log, không in toàn bộ `Environment`).
- [x] Actuator `/env`, `/configprops` phải bảo vệ hoặc tắt ở prod (Spring Boot mặc định mask giá trị nhạy cảm).
- [x] Có quy trình **rotate** secret định kỳ và khi bị lộ.
- [x] Phân quyền least-privilege: service chỉ đọc được secret của chính nó.

---

## 6. Feature flag – "có thể commit nhưng cần quản lý rõ"

```yaml
features:
  enable-new-payment-flow: ${FEATURE_NEW_PAYMENT_FLOW:false}
```

```java
@ConfigurationProperties(prefix = "features")
public record FeatureFlags(boolean enableNewPaymentFlow) {}
```

Quy tắc quản lý:
- Mỗi flag có **owner**, **mục đích**, **ngày tạo và ngày dự kiến xoá**.
- Phân biệt loại: release toggle (ngắn hạn), ops/kill switch (dài hạn), experiment (A/B), permission toggle.
- Xoá flag sau khi rollout 100% để tránh "flag debt".
- Flag ảnh hưởng tiền/thanh toán cần audit log khi bật/tắt.

---

## 7. Kết luận

| Câu hỏi | Trả lời ngắn |
|---|---|
| Config nào là secret? | Password, API key, private key, JWT signing key, OAuth client secret, connection string có chứa credential, certificate key |
| Config thường khác secret thế nào? | Config thường lộ ra không gây hại trực tiếp → có thể commit; secret lộ ra cho phép truy cập/mạo danh → **không bao giờ commit**, lưu trong secret store, mã hoá, phân quyền, rotate |
| Business code có biết URL service không? | Không. Chỉ inject qua `@ConfigurationProperties` / env var / service discovery |

