package uz.yukgo.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Foydalanuvchi bitta yozishmani (e'lon + haydovchi) qayergacha o'qigani: «Xabarlar» dagi o'qilmaganlar soni shundan
 * hisoblanadi. Yozishma ochilganda (xabarlar olinganda) yangilanadi.
 */
@Entity
@Table(name = "message_reads", indexes = @Index(name = "idx_msg_read", columnList = "userId,orderId,driverId"))
@Getter
@Setter
@NoArgsConstructor
public class MessageRead {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long driverId;

    /** Shu ID gacha (shu ham) bo'lgan xabarlar o'qilgan */
    @Column(nullable = false)
    private long lastReadId;
}
