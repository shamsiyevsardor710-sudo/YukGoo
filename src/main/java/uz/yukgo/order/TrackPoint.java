package uz.yukgo.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Haydovchining yo'ldagi GPS nuqtasi. */
@Entity
@Table(name = "track_points", indexes = @Index(name = "idx_track_order", columnList = "orderId,createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class TrackPoint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    private double lat;
    private double lng;
    private Double speedKmh;
    private Double heading;

    private Instant createdAt = Instant.now();
}
