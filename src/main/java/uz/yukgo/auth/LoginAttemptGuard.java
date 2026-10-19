package uz.yukgo.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import uz.yukgo.common.ApiException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Parolni taxmin qilishdan himoya: 10 ta xatodan keyin 15 daqiqa blok. */
@Component
public class LoginAttemptGuard {
    private static final int MAX_FAILS = 10;
    private static final Duration LOCK = Duration.ofMinutes(15);

    private record Entry(int fails, Instant lockedUntil) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public void check(String key) {
        Entry e = entries.get(key);
        if (e != null && e.lockedUntil() != null && e.lockedUntil().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Juda ko'p noto'g'ri urinish. 15 daqiqadan keyin qayta urinib ko'ring");
        }
    }

    public void fail(String key) {
        entries.compute(key, (k, e) -> {
            int fails = (e == null ? 0 : e.fails()) + 1;
            return new Entry(fails, fails >= MAX_FAILS ? Instant.now().plus(LOCK) : null);
        });
    }

    public void success(String key) {
        entries.remove(key);
    }
}
