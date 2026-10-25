package uz.yukgo.payment;

/** Hamyonni to'ldirish mumkin bo'lgan to'lov tizimlari */
public enum PaymentProvider {
    CLICK("Click"),
    PAYME("Payme"),
    PAYNET("Paynet");

    private final String label;

    PaymentProvider(String label) { this.label = label; }

    public String label() { return label; }
}
