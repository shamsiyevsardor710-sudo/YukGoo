package uz.yukgo.wallet;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import uz.yukgo.user.User;

import java.time.Instant;

public final class WalletDtos {
    private WalletDtos() {}

    /** Foydalanuvchining o'z balans tarixi */
    public record TxDto(Long id, long amount, long balanceAfter, TxType type, String typeLabel, Long orderId,
                        String note, Instant createdAt) {
        public static TxDto of(WalletTransaction t) {
            return new TxDto(t.getId(), t.getAmount(), t.getBalanceAfter(), t.getType(), t.getType().label(),
                    t.getOrderId(), t.getNote(), t.getCreatedAt());
        }
    }

    /** Admin panel uchun: kimning balansi o'zgargani bilan */
    public record AdminTxDto(Long id, Long userId, String userName, String userPhone, long amount, long balanceAfter,
                             TxType type, String typeLabel, Long orderId, String note, String actorName,
                             Instant createdAt) {
        public static AdminTxDto of(WalletTransaction t, User u) {
            return new AdminTxDto(t.getId(), t.getUserId(), u == null ? null : u.getFullName(),
                    u == null ? null : u.getPhone(), t.getAmount(), t.getBalanceAfter(), t.getType(),
                    t.getType().label(), t.getOrderId(), t.getNote(), t.getActorName(), t.getCreatedAt());
        }
    }

    /** Hammaga ochiq narxlar (frontend komissiya va shoshilinch narxini ko'rsatadi) */
    public record PricingDto(boolean enabled, double commissionPercent, long commissionMin, long commissionMax,
                             long urgentFee, String topupInfo) {
        public static PricingDto of(MonetizationSettings s) {
            return new PricingDto(s.isEnabled(), s.getCommissionPercent(), s.getCommissionMin(),
                    s.getCommissionMax(), s.getUrgentFee(), s.getTopupInfo());
        }
    }

    public record WalletDto(long balance, PricingDto pricing) {}

    public record SettingsRequest(
            boolean enabled,
            @DecimalMin(value = "0", message = "Foiz manfiy bo'lmaydi")
            @DecimalMax(value = "50", message = "Komissiya 50% dan oshmasin") double commissionPercent,
            @Min(value = 0, message = "Summa manfiy bo'lmaydi") @Max(10_000_000) long commissionMin,
            @Min(value = 0, message = "Summa manfiy bo'lmaydi") @Max(10_000_000) long commissionMax,
            @Min(value = 0, message = "Summa manfiy bo'lmaydi") @Max(10_000_000) long urgentFee,
            @Size(max = 1000) String topupInfo) {}

    public record AdjustRequest(
            @NotBlank(message = "Telefon raqamni kiriting") String phone,
            @NotNull(message = "Summani kiriting") Long amount,
            @Size(max = 300) String note) {}
}
