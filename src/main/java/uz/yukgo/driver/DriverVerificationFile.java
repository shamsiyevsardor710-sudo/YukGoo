package uz.yukgo.driver;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Arizadagi hujjat rasmi (bazada saqlanadi, faqat adminlar ko'radi, qarordan keyin o'chiriladi) */
@Entity
@Table(name = "driver_verification_files", indexes = @Index(name = "idx_dvf_verification", columnList = "verificationId"))
@Getter
@Setter
@NoArgsConstructor
public class DriverVerificationFile {
    public static final String LICENSE = "LICENSE";
    public static final String TECH_PASSPORT = "TECH_PASSPORT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long verificationId;

    /** LICENSE — haydovchilik guvohnomasi, TECH_PASSPORT — texnik pasport */
    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, length = 20)
    private String contentType;

    @Column(nullable = false, length = 6_000_000)
    private byte[] data;
}
