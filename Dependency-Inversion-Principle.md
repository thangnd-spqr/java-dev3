### **1. Nếu muốn đổi sang PayPal thì cần sửa gì?**

Trong đoạn code ban đầu:

* **`PaymentProcessor` phụ thuộc trực tiếp vào `StripeAPI**` (High-level module phụ thuộc vào Low-level module).
* Muốn đổi sang PayPal, bạn bắt buộc phải:
1. Xóa hoặc sửa đổi `StripeAPI stripe = new StripeAPI();` thành `PayPalAPI paypal = new PayPalAPI();` bên trong class `PaymentProcessor`.
2. Sửa lại tên phương thức gọi API (ví dụ: `paypal.makePayment(amount);` thay vì `stripe.charge(amount);`).

---

### **2. Refactor sử dụng Interface**

```java
public interface PaymentGateway {
    void processPayment(double amount);
}

// Stripe Implementation
public class StripePaymentGateway implements PaymentGateway {
    private final StripeAPI stripeAPI = new StripeAPI();

    @Override
    public void processPayment(double amount) {
        stripeAPI.charge(amount);
    }
}

// Class StripeAPI giữ nguyên không đổi
public class StripeAPI {
    public void charge(double amount) {
        System.out.println("Processing payment via Stripe: $" + amount);
    }
}

// PayPal Implementation
public class PayPalPaymentGateway implements PaymentGateway {
    private final PayPalAPI payPalAPI = new PayPalAPI();

    @Override
    public void processPayment(double amount) {
        payPalAPI.makePayment(amount);
    }
}

public class PayPalAPI {
    public void makePayment(double amount) {
        System.out.println("Processing payment via PayPal: $" + amount);
    }
}

public class PaymentProcessor {
    private final PaymentGateway paymentGateway;

    // Inject dependency qua Constructor
    public PaymentProcessor(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    public void process(double amount) {
        paymentGateway.processPayment(amount);
    }
}

```

#### **Sử dụng:**

```java
// Thanh toán qua Stripe
PaymentGateway stripeGateway = new StripePaymentGateway();
PaymentProcessor processor1 = new PaymentProcessor(stripeGateway);
processor1.process(100.0);

// Chuyển sang PayPal mà KHÔNG BỊ SỬA code của PaymentProcessor
PaymentGateway paypalGateway = new PayPalPaymentGateway();
PaymentProcessor processor2 = new PaymentProcessor(paypalGateway);
processor2.process(200.0);

```

---

### **3. Cách test mà không cần Stripe/PayPal thật**

Tạo một class **Mock / Fake Repository** triển khai interface `PaymentGateway` để test trong môi trường Unit Test (Memory) mà không cần gọi API thật ra bên ngoài:

```java
// Mock Implementation dùng cho Testing
public class MockPaymentGateway implements PaymentGateway {
    private double lastChargedAmount = 0;
    private boolean isCalled = false;

    @Override
    public void processPayment(double amount) {
        this.lastChargedAmount = amount;
        this.isCalled = true;
        System.out.println("[TEST MOCK] Payment logged successfully for: $" + amount);
    }

    public double getLastChargedAmount() {
        return lastChargedAmount;
    }

    public boolean isCalled() {
        return isCalled;
    }
}

```

#### **Chạy Unit Test:**

```java
public class PaymentProcessorTest {
    public static void main(String[] args) {
        // Arrange
        MockPaymentGateway mockGateway = new MockPaymentGateway();
        PaymentProcessor processor = new PaymentProcessor(mockGateway);

        // Act
        processor.process(150.0);

        // Assert (Kiểm tra logic)
        if (mockGateway.isCalled() && mockGateway.getLastChargedAmount() == 150.0) {
            System.out.println("✅ Test PASSED!");
        } else {
            System.out.println("❌ Test FAILED!");
        }
    }
}

```