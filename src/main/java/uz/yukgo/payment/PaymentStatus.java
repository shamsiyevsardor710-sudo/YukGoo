package uz.yukgo.payment;

/**
 * To'lov holati:
 *  CREATED   — foydalanuvchi summani tanladi, to'lov sahifasiga o'tdi;
 *  PREPARED  — to'lov tizimi tranzaksiyani ochdi (Click: Prepare, Payme: CreateTransaction), pul hali yechilmagan;
 *  PAID      — pul yechildi, balans to'ldirildi;
 *  CANCELLED — bekor qilindi (to'lanmadi yoki keyin qaytarildi).
 */
public enum PaymentStatus {
    CREATED, PREPARED, PAID, CANCELLED
}
