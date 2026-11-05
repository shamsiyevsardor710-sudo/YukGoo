package uz.yukgo.support;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.yukgo.user.User;

import java.time.Instant;

/**
 * «Biz bilan bog'lanish»: foydalanuvchi yoki haydovchidan taklif, muammo yoki savol. Ega va adminlar «Haydovchilardan» /
 * «Foydalanuvchilardan» bo'limlarida ko'radi, javob yozadi (javob muallifga bildirishnoma bo'lib boradi) yoki yopadi.
 * Hisob o'chirilsa — uning murojaatlari ham o'chiriladi.
 */
@Entity
@Table(name = "support_messages", indexes = {
        @Index(name = "idx_support_list", columnList = "fromDriver,status,id"),
        @Index(name = "idx_support_author", columnList = "author_id,createdAt")
})
@Getter
@Setter
@NoArgsConstructor
public class SupportMessage {
    /** Taklif, muammo, savol, boshqa */
    public enum Topic { IDEA, PROBLEM, QUESTION, OTHER }

    /** Yangi (javobsiz), javob berilgan, yopilgan */
    public enum Status { NEW, ANSWERED, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User author;

    /** Yuborgan paytda haydovchi edimi (mashinasi bor): keyin mashinasini o'chirsa ham o'sha bo'limda qoladi */
    private boolean fromDriver;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Topic topic = Topic.OTHER;

    @Column(nullable = false, length = 1000)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.NEW;

    /** Ega yoki admin javobi (muallifga ko'rinadi) */
    @Column(length = 1000)
    private String reply;

    private Long repliedById;

    /** Javob yozgan admin ismi (o'sha paytdagi) — qaysi admin javob berganini ega ko'rib turadi */
    @Column(length = 130)
    private String repliedByName;

    private Instant repliedAt;

    private Instant createdAt = Instant.now();

    /**
     * Ega va adminlarga boradigan bildirishnoma havolasi. Har bir murojaat uchun alohida (id bilan) — hisob o'chirilganda
     * shu havolali bildirishnomalar ham o'chiriladi (ularda muallif ismi va xabar matni bor).
     */
    public String staffLink() {
        return "#/support/inbox?from=" + (fromDriver ? "drivers" : "users") + "&m=" + id;
    }
}
