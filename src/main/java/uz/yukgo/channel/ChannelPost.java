package uz.yukgo.channel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Buyurtmaning kanaldagi posti — holati o'zgarganda (olindi, yopildi) shu xabar tahrirlanadi. */
@Entity
@Table(name = "channel_posts", indexes = @Index(name = "idx_chpost_order", columnList = "orderId"))
@Getter
@Setter
@NoArgsConstructor
public class ChannelPost {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    private Long feedId;

    @Column(nullable = false)
    private Long chatId;

    @Column(nullable = false)
    private Integer messageId;

    /** Oxirgi chizilgan holat: OPEN, TAKEN yoki CLOSED */
    @Column(length = 10)
    private String state;

    private Instant createdAt = Instant.now();
}
