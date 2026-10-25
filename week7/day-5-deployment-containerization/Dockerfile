# syntax=docker/dockerfile:1.7
# =============================================================================
# Order Service — Multi-stage Dockerfile
#
# Build context: thư mục source của service (../../order-service)
#   docker build -f ../week7/day-5-deployment-containerization/Dockerfile \
#                -t order-service:1.4.0-<git-sha> ../../order-service
#
# Nguyên tắc:
#   - Build stage có JDK + Gradle; runtime stage CHỈ có JRE + app layers.
#   - Pin version base image (không dùng :latest). Production nên pin digest @sha256.
#   - Không COPY .env / secret / key; không khai báo ENV chứa password.
#   - Chạy bằng non-root user (UID 10001).
# =============================================================================

# ===== Stage 1: Build =========================================================
FROM gradle:8.7.0-jdk17 AS build
WORKDIR /workspace

# 1. Copy build script trước → cache layer dependency (chỉ rebuild khi đổi build.gradle)
COPY build.gradle settings.gradle ./
RUN gradle dependencies --no-daemon -q > /dev/null

# 2. Copy source & build fat JAR (test đã chạy ở CI gate trước bước build image)
COPY src ./src
RUN gradle bootJar --no-daemon -x test \
 && cp build/libs/*.jar app.jar

# 3. Tách JAR thành layers (dependencies / loader / snapshot / application)
#    → khi chỉ sửa code nghiệp vụ, chỉ layer "application" thay đổi
RUN java -Djarmode=layertools -jar app.jar extract --destination extracted


# ===== Stage 2: Runtime =======================================================
FROM eclipse-temurin:17.0.11_9-jre-alpine AS runtime

ARG APP_VERSION=dev
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="order-service" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.description="Flash sale Order Service (Spring Boot)"

# Non-root user cố định UID/GID để khớp với securityContext của Kubernetes
RUN addgroup -S -g 10001 app \
 && adduser  -S -u 10001 -G app -h /app -s /sbin/nologin app

WORKDIR /app

# Copy theo thứ tự ít thay đổi → hay thay đổi (tối ưu cache & pull)
COPY --from=build --chown=10001:10001 /workspace/extracted/dependencies/          ./
COPY --from=build --chown=10001:10001 /workspace/extracted/spring-boot-loader/    ./
COPY --from=build --chown=10001:10001 /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/application/           ./

USER 10001:10001

# Chỉ default KHÔNG nhạy cảm. DB_URL / DB_PASSWORD / Kafka credential đến từ runtime.
ENV SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# Docker-level healthcheck chỉ gọi LIVENESS (không phụ thuộc DB/Kafka).
# Trên Kubernetes, probe do kubelet thực hiện → HEALTHCHECK này bị bỏ qua.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://127.0.0.1:${SERVER_PORT}/actuator/health/liveness | grep -q '"status":"UP"' || exit 1

# exec-form → java là PID 1, nhận SIGTERM trực tiếp → Spring graceful shutdown
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

