## 1. Điểm vi phạm Liskov Substitution Principle (LSP)

Class `Vehicle` và các lớp con (`Car`, `ElectricCar`) vi phạm LSP ở các điểm sau:

* `chargeBattery()` chỉ phù hợp với xe điện (hoặc xe hybrid), nhưng `Car` (xe xăng) lại buộc phải kế thừa và quăng ra `UnsupportedOperationException`.
* `startEngine()` gợi ý đến động cơ đốt trong (xăng/dầu), khiến `ElectricCar` không thể triển khai phù hợp và cũng phải quăng ra `UnsupportedOperationException`.
---

## 2. Refactor mã nguồn sử dụng Interface

Để tuân thủ LSP (đồng thời kết hợp với **ISP - Interface Segregation Principle**), ta cần tách các hành vi đặc thù thành các Interface riêng biệt.

### Mã nguồn sau khi Refactor

```java
// Interface gốc chứa hành vi chung cho TẤT CẢ các loại phương tiện
public interface Vehicle {
    void accelerate();
}

// Interface dành riêng cho phương tiện dùng động cơ đốt trong
public interface InternalCombustion {
    void startEngine();
}

// Interface dành riêng cho phương tiện chạy bằng điện
public interface ElectricVehicle {
    void chargeBattery();
}

// Xe xăng: Là Vehicle và có động cơ đốt trong
public class Car implements Vehicle, InternalCombustion {
    @Override
    public void accelerate() {
        System.out.println("Car is accelerating...");
    }

    @Override
    public void startEngine() {
        System.out.println("Starting petrol engine...");
    }
}

// Xe điện: Là Vehicle và có khả năng sạc pin
public class ElectricCar implements Vehicle, ElectricVehicle {
    @Override
    public void accelerate() {
        System.out.println("Electric car is accelerating silently...");
    }

    @Override
    public void chargeBattery() {
        System.out.println("Charging battery...");
    }
}

```

---

## 3. Lợi ích của thiết kế mới

* Bất kỳ lớp con nào triển khai một Interface đều thực sự hỗ trợ toàn bộ các hành vi của Interface đó.
* Không còn tình trạng gọi một phương thức mà lại nhận về `UnsupportedOperationException`.
* Nếu tương lai có thêm loại xe mới (ví dụ: **HybridCar** - vừa chạy xăng vừa chạy điện), bạn chỉ cần cho nó implements cả `Vehicle`, `InternalCombustion` và `ElectricVehicle` mà không làm ảnh hưởng đến các lớp hiện có.
* Nếu có thêm **Bicycle** (Xe đạp - không engine, không battery), lớp này chỉ cần `implements Vehicle`.