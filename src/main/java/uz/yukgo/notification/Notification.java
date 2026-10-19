package uz.yukgo.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "notifications", indexes = @Index(name = "idx_notif_user", columnList = "userId,createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(length = 1000)
    private String body;

    /** Sayt ichidagi havola, masalan: #/orders/15 */
    private String link;

    @Column(name = "is_read")
    private boolean read;

    private Instant createdAt = Instant.now();
}
