package uz.yukgo.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Foydalanuvchi balansi (so'm). Har bir foydalanuvchiga bitta qator, birinchi kerak bo'lganda yaratiladi. */
@Entity
@Table(name = "wallets")
@Getter
@Setter
@NoArgsConstructor
public class Wallet {
    @Id
    private Long userId;

    @Column(nullable = false)
    private long balance;

    private Instant updatedAt = Instant.now();
}
