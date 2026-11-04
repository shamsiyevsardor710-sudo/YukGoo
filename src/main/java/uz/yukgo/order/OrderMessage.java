package uz.yukgo.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.yukgo.user.User;

import java.time.Instant;

/**
 * Buyurtma ichidagi yuboruvchi ↔ haydovchi yozishmasi. Har bir haydovchi bilan alohida ip (driverId): haydovchi tanlanmasdan
 * oldin ham yozishish mumkin. Iplardan oldingi eski xabarlarga driverId ishga tushishda yoziladi (SchemaMaintenance).
 */
@Entity
@Table(name = "order_messages", indexes = {
        @Index(name = "idx_msg_order", columnList = "orderId,id"),
        @Index(name = "idx_msg_thread", columnList = "orderId,driverId,id")})
@Getter
@Setter
@NoArgsConstructor
public class OrderMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    /** Yozishma ipi: shu haydovchi bilan (e'lon egasi yozsa ham — u kimga yozayotgani) */
    private Long driverId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User author;

    @Column(nullable = false, length = 1000)
    private String text;

    private Instant createdAt = Instant.now();
}
