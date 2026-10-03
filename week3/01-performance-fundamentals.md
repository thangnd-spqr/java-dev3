
# 01 - Performance Testing Fundamentals

## 1. Định nghĩa các loại Performance Test

| **Loại test** | **Định nghĩa** |
| --- | --- |
| **Load Test** | Kiểm tra hệ thống hoạt động thế nào dưới mức tải **bình thường hoặc dự kiến** (ví dụ: 1,000 RPS). Mục tiêu: đảm bảo hệ thống đáp ứng được yêu cầu về response time, throughput ở peak traffic. |
| **Stress Test** | Đẩy tải **vượt quá** mức bình thường để tìm **giới hạn tối đa** (breaking point) của hệ thống. Mục tiêu: biết hệ thống sẽ sụp đổ ở đâu và như thế nào. |
| **Spike Test** | Tăng traffic **đột ngột trong thời gian ngắn** (ví dụ: tăng gấp 5 lần trong 2 phút). Mục tiêu: kiểm tra khả năng xử lý traffic đột biến (flash sale, sự kiện viral). |
| **Soak / Endurance Test** | Chạy tải ở mức bình thường nhưng **kéo dài liên tục** (vài giờ đến vài ngày). Mục tiêu: phát hiện memory leak, connection leak, resource exhaustion theo thời gian. |
| **Capacity Test** | Xác định **lượng tải tối đa** mà hệ thống có thể xử lý mà vẫn đáp ứng SLA. Mục tiêu: lập kế hoạch mở rộng (capacity planning). |

---

## 2. Bảng định nghĩa Metric

| **Metric** | **Định nghĩa** | **Ví dụ** |
| --- | --- | --- |
| **Latency / Response Time** | Thời gian từ lúc client gửi request đến lúc nhận response. | `GET /products` mất 120ms từ lúc gửi đến lúc nhận kết quả. |
| **Percentile (p50, p95, p99)** | Phân vị thể hiện phân phối response time. p95 = 800ms nghĩa là 95% request nhanh hơn hoặc bằng 800ms. | p50 = 120ms, p95 = 800ms, p99 = 2.5s → 5% user cuối trải nghiệm chậm gấp ~6 lần so với median. |
| **Throughput (RPS)** | Số request hệ thống xử lý được trong 1 giây. | 500 RPS = hệ thống xử lý 500 requests mỗi giây. |
| **Error Rate** | Tỷ lệ request lỗi / tổng request. | 500 lỗi / 100,000 request = 0.5% error rate. |
| **Concurrent Users** | Số user đồng thời đang tương tác với hệ thống. | 5,000 virtual users gửi request cùng lúc. |
| **Saturation** | Mức độ tài nguyên gần chạm giới hạn (CPU, memory, DB connections, thread pool). | CPU 95%, DB pool 100/100 connections → hệ thống đang saturation. |

> **Lưu ý:** Không dùng **average response time** làm metric duy nhất. Average có thể đẹp nhưng che giấu vấn đề: một nhóm user ở p95/p99 có thể đang có trải nghiệm rất tệ.

---

## 3. Phân tích Requirement – Order API

**Requirement đề bài:**

```
Order API:
- Peak traffic: 1,000 requests/second.
- p95 response time phải dưới 500ms.
- Error rate phải dưới 0.5%.
- Hệ thống cần chịu được traffic liên tục trong 4 giờ.
- Trong flash sale, traffic có thể tăng gấp 5 lần trong 2 phút.
```

---

## 4. Mapping Requirement → Test Type → Metric → Lý do

| **Requirement** | **Test Type** | **Metric cần theo dõi** | **Lý do** |
| --- | --- | --- | --- |
| 1,000 RPS bình thường | **Load Test** | Throughput (RPS), p95 response time, error rate | Cần xác nhận hệ thống đáp ứng được mức tải peak dự kiến (1,000 RPS) với p95 < 500ms và error rate < 0.5%. |
| Traffic liên tục 4 giờ | **Soak Test** | Response time (p95, p99), error rate, CPU, memory, DB connections | Chạy 1,000 RPS liên tục 4 giờ để phát hiện memory leak, connection leak, hoặc resource degradation theo thời gian. |
| Flash sale tăng 5 lần | **Spike Test** | Response time (p95), error rate, throughput, saturation (CPU, thread pool) | Traffic tăng đột ngột từ 1,000 lên 5,000 RPS trong 2 phút. Cần kiểm tra hệ thống có chịu được spike mà không crash hoặc tăng error rate vượt ngưỡng. |
| Tìm giới hạn tối đa | **Stress Test / Capacity Test** | Throughput tối đa, error rate, saturation, breaking point | Tăng tải dần vượt 1,000 RPS cho đến khi hệ thống bắt đầu trả lỗi hoặc response time vượt SLA → xác định capacity tối đa để lập kế hoạch scale. |

