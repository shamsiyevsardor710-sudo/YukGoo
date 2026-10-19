package uz.yukgo.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.yukgo.user.User;

import java.time.Instant;

/** Haydovchining buyurtmaga narx taklifi. */
@Entity
@Table(name = "offers", indexes = @Index(name = "idx_offers_order", columnList = "order_id"))
@Getter
@Setter
@NoArgsConstructor
public class Offer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User driver;

    @Column(nullable = false)
    private Long price;

    @Column(length = 500)
    private String comment;

    /** Yukni qachon olib keta oladi (soat ichida) */
    private Integer pickupInHours;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private OfferStatus status = OfferStatus.PENDING;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
