package uz.yukgo.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Balans harakatlari jurnali: har bir yechish va to'ldirish shu yerda qoladi (o'chirilmaydi). */
@Entity
@Table(name = "wallet_transactions", indexes = {
        @Index(name = "idx_wtx_user", columnList = "userId,createdAt"),
        @Index(name = "idx_wtx_type", columnList = "type,createdAt")
})
@Getter
@Setter
@NoArgsConstructor
public class WalletTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** Musbat — kirim, manfiy — chiqim (so'm) */
    @Column(nullable = false)
    private long amount;

    /** Shu harakatdan keyingi balans */
    @Column(nullable = false)
    private long balanceAfter;

    @Convert(converter = TxTypeConverter.class)
    @Column(nullable = false, length = 20)
    private TxType type;

    private Long orderId;

    @Column(length = 300)
    private String note;

    /** Qo'lda kiritilgan bo'lsa — kim kiritgan */
    private String actorName;

    private Instant createdAt = Instant.now();
}
