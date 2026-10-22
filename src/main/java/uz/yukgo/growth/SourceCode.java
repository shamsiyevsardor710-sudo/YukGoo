package uz.yukgo.growth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Marketing havolasi kodi va uning nomi: kanal, hamkor guruh, dala xodimi, stiker...
 * Havola: https://sayt/?src=KOD yoki https://t.me/BOT?start=src_KOD. Kim shu havoladan ro'yxatdan o'tsa,
 * users.signup_source = KOD bo'ladi va "Manbalar" hisobotida shu nom bilan ko'rinadi.
 */
@Entity
@Table(name = "source_codes")
@Getter
@Setter
@NoArgsConstructor
public class SourceCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    /** Kelishuv shartlari va boshqa eslatmalar (masalan, "har tasdiqlangan haydovchi uchun 15 000 so'm") */
    @Column(length = 300)
    private String note;

    private Instant createdAt = Instant.now();

    private String createdBy;
}
