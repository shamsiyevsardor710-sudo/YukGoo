package uz.yukgo.push;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Android ilovadagi qurilma (Firebase Cloud Messaging tokeni): push-bildirishnoma shu manzilga yuboriladi */
@Entity
@Table(name = "push_tokens", indexes = @Index(name = "idx_push_user", columnList = "userId"))
@Getter
@Setter
@NoArgsConstructor
public class PushToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** Bitta qurilma — bitta qator (boshqa hisob bilan kirilsa, qator o'sha hisobga o'tadi) */
    @Column(nullable = false, length = 512, unique = true)
    private String token;

    @Column(length = 16)
    private String platform;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();
}
