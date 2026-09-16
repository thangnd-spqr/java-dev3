Dưới đây là lời giải chi tiết và mã nguồn refactor cho bài tập của bạn:

---

### 1. Thêm định dạng CSV vào code cũ cần sửa những gì?

Khi muốn hỗ trợ thêm định dạng `CSV`:

* Can thiệp trực tiếp và chỉnh sửa class `ReportGenerator`.
* Thêm một đoạn `else if (format.equals("CSV")) { ... }` bên trong phương thức `generate`.

**Hậu quả:** Vi phạm trực tiếp nguyên lý Open/Closed Principle (OCP). Việc sửa đổi class đã ổn định làm tăng rủi ro phát sinh bug cho các định dạng cũ (`PDF`, `EXCEL`, `JSON`) và buộc phải kiểm thử (re-test) lại toàn bộ class.

---

### 2. Code Refactor dùng Interface

```java
public interface ReportFormatter {
    void format(List<OrderData> data);
}
s
public class PdfReportFormatter implements ReportFormatter {
    @Override
    public void format(List<OrderData> data) {
        // Logic xuất file PDF
    }
}

public class ExcelReportFormatter implements ReportFormatter {
    @Override
    public void format(List<OrderData> data) {
        // Logic xuất file Excel
    }
}

public class JsonReportFormatter implements ReportFormatter {
    @Override
    public void format(List<OrderData> data) {
        // Logic xuất file JSON
    }
}

// Khi cần thêm CSV, chỉ cần tạo thêm class mới mà không chạm vào code cũ:
public class CsvReportFormatter implements ReportFormatter {
    @Override
    public void format(List<OrderData> data) {
        // Logic xuất file CSV
    }
}

public class ReportGenerator {
    private final ReportFormatter formatter;

    public ReportGenerator(ReportFormatter formatter) {
        this.formatter = formatter;
    }

    public void generate(List<OrderData> data) {
        formatter.format(data);
    }
}

```

#### Cách sử dụng

```java
List<OrderData> data = new ArrayList<>();

// Xuất PDF
ReportGenerator pdfGenerator = new ReportGenerator(new PdfReportFormatter());
pdfGenerator.generate(data);

// Xuất CSV (tính năng mới)
ReportGenerator csvGenerator = new ReportGenerator(new CsvReportFormatter());
csvGenerator.generate(data);

```

---

### 3. Lợi ích của thiết kế mới

* **Tuân thủ OCP:** Khi mở rộng thêm định dạng mới (CSV, HTML, XML,...), bạn chỉ cần **viết thêm class mới** hiện thực `ReportFormatter` mà **không cần sửa một dòng code nào** trong `ReportGenerator` hay các class hiện có.
* **Giảm rủi ro (Low Risk):** Tránh hiện tượng "sửa chỗ này hỏng chỗ kia" (side-effects) trên các tính năng đang hoạt động ổn định.
* **Dễ unit test:** Có thể dễ dàng mock `ReportFormatter` để test riêng lẻ từng class mà không phụ thuộc vào nhau.
* **Dễ phân chia công việc:** Các lập trình viên có thể tạo các định dạng file khác nhau song song mà không sợ bị xung đột code (conflict) trên cùng một file.