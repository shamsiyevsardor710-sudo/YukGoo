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

    public JwtService(AppProperties props) {
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
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** Token to'g'ri bo'lsa foydalanuvchi ID sini qaytaradi, aks holda null. */
    public Long parseUserId(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
