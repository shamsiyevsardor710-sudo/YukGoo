package uz.yukgo.review;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.yukgo.user.User;

import java.time.Instant;

/** Baholash: buyurtma yakunlangach yuboruvchi haydovchini, haydovchi yuboruvchini baholaydi. */
@Entity
@Table(name = "reviews",
        uniqueConstraints = @UniqueConstraint(name = "uk_review_order_author", columnNames = {"orderId", "author_id"}),
        indexes = @Index(name = "idx_review_target", columnList = "target_id,createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class Review {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User author;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User target;

    /** 1..5 */
    @Column(nullable = false)
    private int stars;

    @Column(length = 1000)
    private String comment;

    /** Haydovchi sifatida baholandimi (true) yoki yuboruvchi sifatida (false) */
    private boolean targetWasDriver;

    private Instant createdAt = Instant.now();
}
