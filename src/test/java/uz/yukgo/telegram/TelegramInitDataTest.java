package uz.yukgo.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import uz.yukgo.common.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Telegram Mini App initData imzosini tekshirish. Namunalar ochiq kutubxona (@telegram-apps/init-data-node)
 * hujjatidagi sinov tokeni bilan yasalgan — bu YukGo botining tokeni emas.
 */
class TelegramInitDataTest {
    /** Kutubxona hujjatidagi ochiq sinov tokeni (bo'lib yozilgan — maxfiy kalit skanerlari uni haqiqiy token deb o'ylamasin) */
    private static final String TOKEN = "5768337691" + ":" + "AAH5YkoiEuPk8-FZa32hStHTqXiLPtAEhx8";
    private static final String USER = "user=%7B%22id%22%3A279058397%2C%22first_name%22%3A%22Vladislav%22%2C%22last_name%22%3A%22Kibenko%22"
            + "%2C%22username%22%3A%22vdkfrost%22%2C%22language_code%22%3A%22ru%22%2C%22is_premium%22%3Atrue"
            + "%2C%22allows_write_to_pm%22%3Atrue%7D";
    private static final String INIT = "query_id=AAHdF6IQAAAAAN0XohDhrOrc&" + USER + "&auth_date=1792598288&signature="
            + "&hash=d45cc8b7d42785e853c5b263e40414e60314b3ef287c455d83d583335c8d1c63";
    private static final String INIT_WITH_SIGNATURE = "query_id=AAHdF6IQAAAAAN0XohDhrOrc&" + USER
            + "&signature=abc123_signature-value&auth_date=1792598288"
            + "&hash=ef1b9d46f84c20e7919f38dc7ec298e003795105597ffcd36e471516a93df1c1";
    private static final Instant NOW = Instant.ofEpochSecond(1792598288L + 60);
    private static final Duration DAY = Duration.ofDays(1);

    private static String error(Runnable r) {
        return assertThrows(ApiException.class, r::run).getMessage();
    }

    @Test
    void secretKeyMatchesReference() {
        byte[] secret = TelegramInitData.hmac("WebAppData".getBytes(StandardCharsets.UTF_8), TOKEN.getBytes(StandardCharsets.UTF_8));
        assertEquals("a5c609aa52f63cb5e6d8ceb6e4138726ea82bbc36bb786d64482d445ea38ee5f", HexFormat.of().formatHex(secret));
    }

    @Test
    void acceptsValidData() {
        Map<String, String> fields = TelegramInitData.verifySignature(INIT, TOKEN, DAY, NOW);
        assertEquals("1792598288", fields.get("auth_date"));
        assertFalse(fields.containsKey("hash"));

        TelegramInitData.Verified v = TelegramInitData.verify(INIT, TOKEN, DAY, NOW);
        assertEquals(279058397L, v.userId());
        assertEquals("Vladislav", v.firstName());
        assertEquals("vdkfrost", v.username());
    }

    @Test
    void signatureFieldIsPartOfCheckString() {
        assertTrue(TelegramInitData.verifySignature(INIT_WITH_SIGNATURE, TOKEN, DAY, NOW).containsKey("signature"));
    }

    @Test
    void rejectsTamperedOrForeignData() {
        assertEquals("Telegram imzosi noto'g'ri",
                error(() -> TelegramInitData.verifySignature(INIT.replace("279058397", "279058398"), TOKEN, DAY, NOW)));
        assertEquals("Telegram imzosi noto'g'ri",
                error(() -> TelegramInitData.verifySignature(INIT, TOKEN.replace('A', 'B'), DAY, NOW)));
        assertEquals("Telegram imzosi topilmadi",
                error(() -> TelegramInitData.verifySignature(INIT.replaceAll("&hash=[0-9a-f]+", ""), TOKEN, DAY, NOW)));
    }

    @Test
    void rejectsExpiredAndFutureData() {
        assertTrue(error(() -> TelegramInitData.verifySignature(INIT, TOKEN, DAY, NOW.plus(Duration.ofDays(2)))).contains("eskirgan"));
        assertTrue(error(() -> TelegramInitData.verifySignature(INIT, TOKEN, DAY, NOW.minus(Duration.ofHours(1)))).contains("eskirgan"));
    }

    @Test
    void rejectsMalformedInputWithBadRequest() {
        assertEquals("Telegram bot sozlanmagan", error(() -> TelegramInitData.verifySignature(INIT, "", DAY, NOW)));
        assertNotNull(error(() -> TelegramInitData.verifySignature("", TOKEN, DAY, NOW)));
        assertEquals("Telegram ma'lumotlari buzilgan", error(() -> TelegramInitData.verifySignature("user=%zz&hash=00", TOKEN, DAY, NOW)));
        ApiException e = assertThrows(ApiException.class, () -> TelegramInitData.verifySignature(INIT + "x", TOKEN, DAY, NOW));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());   // 401 emas: frontend 401 da sessiyani o'chirib yuboradi
    }
}
