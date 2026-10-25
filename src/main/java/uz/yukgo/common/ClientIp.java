package uz.yukgo.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Joriy so'rov yuborgan mijozning IP manzili. */
public final class ClientIp {
    private ClientIp() {}

    /**
     * Railway'da ilova proksi ortida turadi: haqiqiy manzil X-Real-IP sarlavhasida (proksi uni har doim o'zi yozadi,
     * mijoz yuborgan qiymat almashtiriladi). HTTP so'rovdan tashqarida (masalan, fon vazifasida) — null.
     */
    public static String current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) return null;
        return of(sra.getRequest());
    }

    static String of(HttpServletRequest r) {
        String real = r.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        String fwd = r.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return r.getRemoteAddr();
    }
}
