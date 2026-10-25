package uz.yukgo.payment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;

public final class PaymentDtos {
    private PaymentDtos() {}

    /**
     * Foydalanuvchiga ko'rsatiladigan to'lov usullari (daromad tizimi o'chiq bo'lsa — hammasi false).
     * @param paynetAccount Paynet ilovasida kiritiladigan hisob raqami (foydalanuvchi ID si)
     * @param paynetLink    Paynet ilovasini shu hisob bilan ochadigan havola (PAYNET_MERCHANT_ID berilgan bo'lsa)
     */
    public record Options(boolean click, boolean payme, boolean paynet, long min, long max,
                          String paynetAccount, String paynetLink) {}

    public record TopupRequest(@NotNull(message = "To'lov usulini tanlang") PaymentProvider provider,
                               @Min(value = 1, message = "Summani kiriting") long amount) {}

    /** @param id Click/Payme uchun to'lov raqami (holatini tekshirish uchun); Paynet'da — null */
    public record TopupResponse(Long id, PaymentProvider provider, long amount, String payUrl, String paynetAccount) {}

    /** @param status CREATED (to'lov kutilmoqda), PENDING (to'lov tizimida), PAID, CANCELLED */
    public record PaymentDto(Long id, PaymentProvider provider, long amount, String status, Instant createdAt, Instant paidAt) {}

    /** Admin: bitta to'lov (yoki qayta urinish — parentId) */
    public record AdminPaymentDto(Long id, Long parentId, Long userId, String userName, String userPhone, PaymentProvider provider,
                                  long amount, PaymentStatus status, String providerTransId, Instant createdAt, Instant paidAt,
                                  Instant cancelledAt, String note, boolean test) {}

    /**
     * Admin: qaysi to'lov tizimlari ulangan va ularga beriladigan manzillar.
     * @param urls clickPrepare, clickComplete, payme, paynet
     */
    public record AdminSetup(boolean click, boolean payme, boolean paymeTest, boolean paynet, boolean paynetCredentials,
                             boolean paynetServiceSet, long min, long max, Map<String, String> urls) {}
}
