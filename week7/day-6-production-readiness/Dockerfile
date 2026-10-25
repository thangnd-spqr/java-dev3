# =============================================================================
# STAGE 1: Build & Extract Spring Boot Layered JAR
# =============================================================================
FROM gradle:8.7-jdk17-alpine AS builder

WORKDIR /workspace

# 1. Copy file khai báo dependency trước để tận dụng Docker Layer Cache
COPY build.gradle settings.gradle ./
RUN gradle dependencies --no-daemon

# 2. Copy source code và đóng gói fat JAR (bỏ qua test vì test đã chạy ở CI gate)
COPY src ./src
RUN gradle bootJar --no-daemon -x test

# 3. Tách fat JAR thành các layer chuẩn của Spring Boot (dependencies, loader, app)
#    giúp tăng tốc độ build và giảm dung lượng pull image khi chỉ sửa code nghiệp vụ
WORKDIR /workspace/extracted
RUN java -Djarmode=layertools -jar /workspace/build/libs/*.jar extract


# =============================================================================
# STAGE 2: Production Runtime Image (Minimal JRE, Non-root, Container-aware JVM)
# =============================================================================
FROM eclipse-temurin:17-jre-alpine AS runtime

LABEL org.opencontainers.image.title="order-service" \
      org.opencontainers.image.description="Production-ready Spring Boot Order Service" \
      org.opencontainers.image.version="1.0.0"

# 1. Tạo non-root group & user cố định UID/GID = 10001 (chuẩn bảo mật Kubernetes PodSecurity)
RUN addgroup -S appgroup -g 10001 && \
    adduser -S appuser -u 10001 -G appgroup -h /app -s /sbin/nologin && \
    mkdir -p /app /tmp/heapdumps && \
    chown -R appuser:appgroup /app /tmp/heapdumps

WORKDIR /app

# 2. Copy các layer theo thứ tự từ ít thay đổi nhất -> hay thay đổi nhất
COPY --from=builder --chown=appuser:appgroup /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/extracted/application/ ./

# 3. Chuyển sang thực thi bằng non-root user (KHÔNG chạy root trong container)
USER 10001:10001

# 4. Cấu hình biến môi trường mặc định KHÔNG chứa secret
#    (Secret bắt buộc phải truyền từ runtime environment / Vault / K8s Secret)
ENV SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=prod \
    SHUTDOWN_TIMEOUT=30s \
    JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:InitialRAMPercentage=50.0 \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -XX:MaxGCPauseMillis=200 \
               -XX:+HeapDumpOnOutOfMemoryError \
               -XX:HeapDumpPath=/tmp/heapdumps/heapdump.hprof \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

# 5. Đảm bảo gửi tín hiệu SIGTERM để kích hoạt Spring Boot Graceful Shutdown
STOPSIGNAL SIGTERM

# 6. Docker-level Healthcheck chỉ gọi Liveness probe nội tại (không phụ thuộc DB/External)
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:${SERVER_PORT}/actuator/health/liveness | grep -q '"status":"UP"' || exit 1

# 7. Dùng `exec` để tiến trình `java` nhận PID 1 và bắt trực tiếp tín hiệu SIGTERM
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]

