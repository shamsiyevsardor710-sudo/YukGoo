package uz.yukgo.order;

/**
 * NEW → ACCEPTED → IN_TRANSIT → DELIVERED
 *  └────────┴──→ CANCELLED
 */
public enum OrderStatus {
    /** E'lon qilingan, haydovchilar taklif yuboradi */
    NEW,
    /** Haydovchi tanlangan, yukni olishga ketyapti */
    ACCEPTED,
    /** Yuk olindi, yo'lda */
    IN_TRANSIT,
    /** Qabul qiluvchi kodi bilan topshirildi */
    DELIVERED,
    CANCELLED;

    public boolean isActive() {
        return this == ACCEPTED || this == IN_TRANSIT;
    }
}
