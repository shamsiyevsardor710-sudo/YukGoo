package uz.yukgo.user;

import org.springframework.http.HttpStatus;
import uz.yukgo.common.ApiException;

/**
 * Foydalanish shartlari (static/terms.html). Google Play talabi: foydalanuvchi e'lon, taklif, xabar, sharh kabi
 * kontent yaratishdan oldin shartlarni qabul qilgan bo'lishi kerak — shartlarda taqiqlangan kontent va xatti-harakatlar,
 * shikoyat va bloklash tartibi yozilgan.
 */
public final class Terms {
    /**
     * Joriy versiya. terms.html jiddiy o'zgarsa oshiriladi — hamma keyingi kirishda shartlarni qayta qabul qiladi.
     * (Matndagi kichik tuzatishlar uchun oshirish shart emas.)
     */
    public static final int VERSION = 1;

    private Terms() {}

    /** Kontent yaratuvchi amallardan oldin. 428 — ilova buni ko'rib, shartlarni qabul qilish oynasini ochadi. */
    public static void require(User u) {
        if (u != null && !u.hasAcceptedTerms()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "Davom etish uchun foydalanish shartlarini qabul qiling");
        }
    }
}
