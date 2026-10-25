package uz.yukgo.sms;

/** Haqiqiy telefonga SMS yuborish (tasdiqlash kodlari uchun). */
public interface SmsSender {
    /** SMS xizmati sozlanganmi (login va parol berilgan) */
    boolean enabled();

    /**
     * Bitta SMS yuboradi.
     * @param phone "+998XXXXXXXXX" ko'rinishidagi raqam
     * @return true — xizmat SMS'ni qabul qildi (telefonga yetib borishi operatorga bog'liq)
     */
    boolean send(String phone, String text);
}
