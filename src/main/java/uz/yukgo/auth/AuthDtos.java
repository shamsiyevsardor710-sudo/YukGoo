package uz.yukgo.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import uz.yukgo.user.UserDtos.MeDto;

public final class AuthDtos {
    private AuthDtos() {}

    public record PhoneRequest(@NotBlank(message = "Telefon raqamni kiriting") String phone) {}

    public record RequestIdBody(@NotBlank String requestId) {}

    public record VerifyRequest(
            @NotBlank String requestId,
            @NotBlank(message = "Kodni kiriting") @Pattern(regexp = "\\d{6}", message = "Kod 6 ta raqamdan iborat") String code) {}

    public record RegisterCompleteRequest(
            @NotBlank String requestId,
            @NotBlank(message = "Ismni kiriting") @Size(min = 2, max = 60, message = "Ism 2-60 belgi bo'lsin") String firstName,
            @NotBlank(message = "Familiyani kiriting") @Size(min = 2, max = 60, message = "Familiya 2-60 belgi bo'lsin") String lastName,
            @NotBlank(message = "Parolni kiriting") @Size(min = 6, max = 64, message = "Parol kamida 6 belgi bo'lsin") String password,
            @NotNull(message = "Viloyatni tanlang") Long regionId,
            @NotNull(message = "Tumanni tanlang") Long districtId,
            @NotBlank(message = "Qishloq/mahallani kiriting") @Size(max = 120) String village,
            @NotBlank(message = "Uy manzilini kiriting") @Size(max = 120) String house,
            /** Telegram Mini App ichida bo'lsa — Telegram.WebApp.initData (hisob shu Telegramga ulanadi) */
            @Size(max = 8192) String telegramInitData,
            /** Qaysi havoladan keldi (kanal, hamkor, dala xodimi kodi) — ixtiyoriy */
            @Size(max = 64) String source,
            /** Kim taklif qildi (taklif havolasidagi foydalanuvchi ID si) — ixtiyoriy */
            @Size(max = 24) String ref) {}

    /** Ism + familiya + parol bilan kirish. Bir xil ismli bir nechta odam bo'lsa telefon ham so'raladi. */
    public record LoginRequest(String firstName, String lastName, String phone,
                               @NotBlank(message = "Parolni kiriting") String password,
                               /** Telegram Mini App ichida bo'lsa — hisob shu Telegramga ulanadi (parol shu so'rovda isbotlanadi) */
                               @Size(max = 8192) String telegramInitData) {}

    public record ResetPasswordRequest(
            @NotBlank String requestId,
            @NotBlank @Size(min = 6, max = 64, message = "Parol kamida 6 belgi bo'lsin") String newPassword,
            @Size(max = 8192) String telegramInitData) {}

    public record AuthResponse(String token, MeDto user) {}

    /** Telegram Mini App: Telegram.WebApp.initData satri (imzoli) */
    public record TelegramInitRequest(
            @NotBlank(message = "Telegram ma'lumotlari kelmadi") @Size(max = 8192) String initData) {}
}
