package uz.yukgo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import uz.yukgo.config.AppProperties;
import uz.yukgo.user.User;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final SecretKey key;
    private final Duration ttl;

    /** application.yml dagi sinov kaliti. U GitHub'da hammaga ochiq — u bilan istalgan hisob nomidan token yasash mumkin. */
    static final String DEV_SECRET = "ZGV2LXNlY3JldC1rZXktY2hhbmdlLW1lLWluLXByb2R1Y3Rpb24tMTIzNDU2Nzg5MA==";

    public JwtService(AppProperties props) {
        if (!props.devMode() && DEV_SECRET.equals(props.jwt().secret() == null ? null : props.jwt().secret().trim())) {
            throw new IllegalStateException("JWT_SECRET berilmagan: ochiq sinov kaliti bilan ishga tushirib bo'lmaydi. "
                    + "Railway → Variables'da JWT_SECRET ga uzun tasodifiy qiymat yozing (README, \"Railway'ga joylash\").");
        }
        byte[] bytes;
        try {
            bytes = Decoders.BASE64.decode(props.jwt().secret());
        } catch (Exception e) {
            bytes = props.jwt().secret().getBytes();
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET kamida 32 bayt bo'lishi kerak");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttl = Duration.ofHours(props.jwt().expirationHours());
    }

    public String generate(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("role", user.getRole().name())
                .claim("tv", user.currentTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** Imzosi va muddati tekshirilgan token: foydalanuvchi ID si va token versiyasi ("tv"; eski tokenlarda yo'q — 0) */
    public record TokenInfo(Long userId, int tokenVersion) {}

    /** Token to'g'ri bo'lsa uning ma'lumotlarini qaytaradi, aks holda null. */
    public TokenInfo parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            Object tv = claims.get("tv");
            return new TokenInfo(Long.valueOf(claims.getSubject()), tv instanceof Number n ? n.intValue() : 0);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Token to'g'ri bo'lsa foydalanuvchi ID sini qaytaradi, aks holda null. */
    public Long parseUserId(String token) {
        TokenInfo t = parse(token);
        return t == null ? null : t.userId();
    }
}
