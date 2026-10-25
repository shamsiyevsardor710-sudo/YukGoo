package uz.yukgo.config;

import org.junit.jupiter.api.Test;
import uz.yukgo.common.Phones;
import uz.yukgo.telegram.TelegramClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Logda sir chiqmasligi va Railway'dagi xavfli sozlamalar haqida ogohlantirishlar. */
class SafetyChecksTest {
    // Haqiqiy token emas, faqat shakli bir xil (manbada token ko'rinishidagi satr bo'lmasligi uchun yig'iladi)
    private static final String FAKE_TOKEN = "123456789" + ":" + "AAH4fake" + "x".repeat(27);

    @Test
    void botTokenIsRemovedFromErrorMessages() {
        String msg = "I/O error on GET request for \"https://api.telegram.org/bot" + FAKE_TOKEN + "/getUpdates\": Read timed out";
        assertEquals("I/O error on GET request for \"https://api.telegram.org/bot***/getUpdates\": Read timed out",
                TelegramClient.redact(msg));
        assertFalse(TelegramClient.safe(new RuntimeException(msg)).contains("AAH4fake"));
        assertEquals("chat=-1001234567890 at 12:30:45", TelegramClient.redact("chat=-1001234567890 at 12:30:45"));
        assertEquals("IllegalStateException", TelegramClient.safe(new IllegalStateException()));
    }

    @Test
    void phoneIsMaskedForLogs() {
        assertEquals("+998*******67", Phones.mask("+998901234567"));
        assertEquals("***", Phones.mask(null));
    }

    @Test
    void warnsAboutDataInsideRailwayContainer() {
        String h2 = "jdbc:h2:file:./data/yukgo;MODE=PostgreSQL";
        assertTrue(StartupChecks.warnings(Map.of("HOME", "/root"), h2, "/app/uploads").isEmpty());

        List<String> w = StartupChecks.warnings(Map.of("RAILWAY_PROJECT_ID", "p"), h2, "/app/uploads");
        assertEquals(2, w.size());

        assertTrue(StartupChecks.warnings(Map.of("RAILWAY_PROJECT_ID", "p", "RAILWAY_VOLUME_MOUNT_PATH", "/app/uploads"),
                "jdbc:postgresql://postgres.railway.internal:5432/railway", "/app/uploads").isEmpty());
        assertFalse(StartupChecks.onVolume("/app/uploads2", "/app/uploads"));
    }
}
