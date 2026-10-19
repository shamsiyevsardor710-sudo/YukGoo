package uz.yukgo.admin;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.yukgo.user.User;

import java.time.Instant;

/** Foydalanuvchi shikoyati yoki adminga murojaat. */
@Entity
@Table(name = "reports")
@Getter
@Setter
@NoArgsConstructor
public class Report {
    public enum Status { OPEN, RESOLVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User author;

    /** Kimga shikoyat (ixtiyoriy — umumiy murojaat bo'lishi ham mumkin) */
    @ManyToOne(fetch = FetchType.LAZY)
    private User target;

    private Long orderId;

    @Column(nullable = false, length = 1000)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.OPEN;

    @Column(length = 500)
    private String resolution;

    private Long resolvedById;
    private Instant resolvedAt;
    private Instant createdAt = Instant.now();
}
