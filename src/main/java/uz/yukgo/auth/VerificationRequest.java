package uz.yukgo.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "verification_requests", indexes = {
        @Index(name = "idx_vr_phone", columnList = "phone"),
        @Index(name = "idx_vr_token", columnList = "linkToken", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class VerificationRequest {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationPurpose purpose;

    /** Telegram deep-link uchun qisqa token: t.me/bot?start=TOKEN */
    @Column(nullable = false, length = 32)
    private String linkToken;

    /** CHANGE_PHONE uchun — kim o'zgartiryapti */
    private Long userId;

    private String codeHash;
    private Instant codeSentAt;
    private Instant codeExpiresAt;

    private int attempts;
    private boolean verified;
    private Instant verifiedAt;
    private boolean consumed;

    private Instant createdAt = Instant.now();
}
