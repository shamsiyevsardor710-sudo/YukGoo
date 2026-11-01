package uz.yukgo.trip;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.yukgo.location.District;
import uz.yukgo.location.Region;
import uz.yukgo.user.User;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Haydovchining reys e'loni: "Ertaga Toshkent → Samarqand ketyapman, 2 t joy bor".
 * Yuboruvchilar shu e'lonlardan mos haydovchini topib to'g'ridan-to'g'ri bog'lanadi.
 */
@Entity
@Table(name = "trips", indexes = @Index(name = "idx_trips_route", columnList = "from_region_id,to_region_id,departAt"))
@Getter
@Setter
@NoArgsConstructor
public class Trip {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User driver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_region_id")
    private Region fromRegion;

    @ManyToOne(fetch = FetchType.LAZY)
    private District fromDistrict;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_region_id")
    private Region toRegion;

    @ManyToOne(fetch = FetchType.LAZY)
    private District toDistrict;

    /** Jo'nash vaqti (Toshkent vaqti). «Har kuni» reysda — birinchi kun va odatdagi soati */
    @Column(nullable = false)
    private LocalDateTime departAt;

    /** Har kuni shu yo'nalishda qatnaydi (taksi, shatl) — sanasi o'tsa ham faol qoladi. null — eski reyslar (yo'q) */
    private Boolean daily;

    /** Bo'sh o'rinlar (yo'lovchilar uchun), ixtiyoriy */
    private Integer seats;

    /** (eski) bo'sh joy, kg va hajm — endi so'ralmaydi */
    private Integer availableKg;
    private Double availableM3;

    /** Taxminiy narx (masalan, 1 kg uchun yoki butun mashina) */
    @Column(length = 100)
    private String priceNote;

    @Column(length = 500)
    private String comment;

    private boolean active = true;

    private Instant createdAt = Instant.now();

    public boolean isDaily() {
        return Boolean.TRUE.equals(daily);
    }
}
