package uz.yukgo.channel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

/**
 * Yangi e'lonlar avtomatik chiqadigan Telegram kanal yoki guruh (YukGo'ning o'z kanali yoki hamkorniki).
 * Bot u yerda admin (kanalda — xabar yuborish huquqi bilan) bo'lishi kerak.
 */
@Entity
@DynamicUpdate   // admin o'zgartirishi va postlar soni bir-birini ustidan yozmasin
@Table(name = "channel_feeds")
@Getter
@Setter
@NoArgsConstructor
public class ChannelFeed {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Telegram chat ID si (kanallarda -100... ko'rinishida) */
    @Column(nullable = false, unique = true)
    private Long chatId;

    @Column(length = 200)
    private String title;

    @Column(length = 64)
    private String username;

    /** channel, supergroup yoki group */
    @Column(length = 20)
    private String chatType;

    /** Faqat shu viloyatdan yoki shu viloyatga yuklar (null — hammasi) */
    private Long regionId;

    private boolean active = true;

    /** Kanal postidagi tugmadan kelganlar shu kod bilan hisoblanadi (masalan "ch3") */
    @Column(length = 40)
    private String sourceCode;

    private long postsCount;

    private Instant createdAt = Instant.now();

    private String addedBy;
}
