package uz.yukgo.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Hamyonni to'ldirish uchun bitta to'lov urinishi. Bitta to'lov tizimi tranzaksiyasi — bitta qator:
 * foydalanuvchi to'lov sahifasida qayta urinsa (masalan, boshqa karta bilan), asl qatorning "farzandi" yaratiladi
 * (parentId), lekin bitta oiladan faqat bittasi to'lanishi mumkin.
 */
@Entity
@Table(name = "payments",
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_provider_trans", columnNames = {"provider", "providerTransId"}),
        indexes = {
                @Index(name = "idx_payment_user", columnList = "userId,createdAt"),
                @Index(name = "idx_payment_parent", columnList = "parentId")
        })
@Getter
@Setter
@NoArgsConstructor
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentProvider provider;

    /** So'm */
    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentStatus status = PaymentStatus.CREATED;

    /** Asl to'lov (qayta urinishda); asl qatorning o'zida — null */
    private Long parentId;

    /** To'lov tizimidagi tranzaksiya ID si (Click: click_trans_id, Payme: id, Paynet: transactionId) */
    @Column(length = 64)
    private String providerTransId;

    /** Qo'shimcha raqam: Click to'lov hujjati (click_paydoc_id) */
    @Column(length = 64)
    private String providerRef;

    /** Payme: tranzaksiya holati (1, 2, -1, -2) va bekor qilish sababi */
    private Integer providerState;
    private Integer cancelReason;
    /** Payme: to'lov tizimidagi yaratilish vaqti (ms) */
    private Long providerTime;

    private Instant createdAt = Instant.now();
    private Instant preparedAt;
    private Instant paidAt;
    private Instant cancelledAt;

    @Column(length = 300)
    private String note;

    /** Admin ochgan sinov to'lovi: to'lansa ham balansga yozilmaydi, bekor qilinsa ham balansdan yechilmaydi */
    private Boolean testPayment;

    /** Payme sinov kassasi (test.paycom.uz) orqali o'tgan tranzaksiya — haqiqiy pul emas, haqiqiy hisobotga kirmaydi */
    private Boolean sandbox;

    public Long rootId() {
        return parentId != null ? parentId : id;
    }

    public boolean isTest() {
        return Boolean.TRUE.equals(testPayment);
    }

    public boolean isSandboxTransaction() {
        return Boolean.TRUE.equals(sandbox);
    }
}
