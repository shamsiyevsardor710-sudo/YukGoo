package uz.yukgo.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Platforma daromadi sozlamalari — bazada bitta qator (id = 1).
 * Boshlang'ich holatda o'chiq: hech kimdan pul yechilmaydi. Ega admin paneldan yoqadi.
 */
@Entity
@Table(name = "monetization_settings")
@Getter
@Setter
@NoArgsConstructor
public class MonetizationSettings {
    public static final long ID = 1L;

    @Id
    private Long id = ID;

    /** Asosiy kalit: o'chiq bo'lsa komissiya ham, shoshilinch to'lovi ham olinmaydi */
    private boolean enabled;

    /** Haydovchiga buyurtma berilganda yechiladigan komissiya, narxning foizi */
    private double commissionPercent = 5.0;

    /** Eng kam komissiya, so'm */
    private long commissionMin = 3000;

    /** Eng ko'p komissiya, so'm (0 — cheklovsiz) */
    private long commissionMax = 50000;

    /** "Shoshilinch" e'lon narxi, so'm (0 — bepul) */
    private long urgentFee = 5000;

    /** Balansni qanday to'ldirish haqida foydalanuvchilarga ko'rsatiladigan matn */
    @Column(length = 1000)
    private String topupInfo;

    private Instant updatedAt = Instant.now();

    private String updatedBy;
}
