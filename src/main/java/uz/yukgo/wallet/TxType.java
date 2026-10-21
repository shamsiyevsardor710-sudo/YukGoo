package uz.yukgo.wallet;

/** Balans harakati turi */
public enum TxType {
    TOPUP("Balans to'ldirildi"),
    COMMISSION("Komissiya"),
    COMMISSION_REFUND("Komissiya qaytarildi"),
    URGENT_FEE("Shoshilinch e'lon"),
    ADJUSTMENT("Tuzatish");

    private final String label;

    TxType(String label) { this.label = label; }

    public String label() { return label; }
}
