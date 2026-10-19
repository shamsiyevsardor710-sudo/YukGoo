package uz.yukgo.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Buyurtma tarixi (timeline). */
@Entity
@Table(name = "order_events", indexes = @Index(name = "idx_events_order", columnList = "orderId"))
@Getter
@Setter
@NoArgsConstructor
public class OrderEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(length = 15)
    private OrderStatus status;

    @Column(nullable = false, length = 300)
    private String text;

    private Long actorId;
    private String actorName;

    private Instant createdAt = Instant.now();
}
