package uz.yukgo.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * Holatni o'zgartirishdan oldin asl to'lov qatorini bloklash. Bitta buyurtmaga tegishli hamma o'zgarishlar shu blok
     * ostida bajariladi — to'lov tizimi bir so'rovni ikki marta yoki parallel yuborsa ham, bir marta ishlanadi.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.provider = :provider and p.providerTransId = :transId")
    Optional<Payment> lockByProviderTrans(@Param("provider") PaymentProvider provider, @Param("transId") String transId);

    Optional<Payment> findByProviderAndProviderTransId(PaymentProvider provider, String providerTransId);

    /** To'lov tizimi tranzaksiyasi qaysi buyurtmaga tegishli (obyektni yuklamasdan — avval buyurtma bloklanadi) */
    @Query("select coalesce(p.parentId, p.id) from Payment p where p.provider = :provider and p.providerTransId = :transId")
    Optional<Long> rootIdOf(@Param("provider") PaymentProvider provider, @Param("transId") String transId);

    /** Asl to'lov va uning qayta urinishlari */
    @Query("select p from Payment p where p.id = :rootId or p.parentId = :rootId order by p.id")
    List<Payment> family(@Param("rootId") Long rootId);

    /** Foydalanuvchining shu tizimdagi eng oxirgi to'lovi */
    Optional<Payment> findFirstByUserIdAndProviderOrderByIdDesc(Long userId, PaymentProvider provider);

    /** Admin ro'yxati: eng yangilari birinchi */
    Page<Payment> findAllByOrderByIdDesc(Pageable pageable);

    /** Foydalanuvchi so'nggi vaqtda nechta to'lov ochgan (ko'p bosishdan himoya) */
    long countByUserIdAndCreatedAtAfter(Long userId, Instant since);

    /** Payme hisoboti (GetStatement): Payme'dagi yaratilish vaqti (ms) berilgan oraliqda bo'lgan tranzaksiyalar */
    @Query("select p from Payment p where p.provider = :provider and p.providerTransId is not null "
            + "and p.providerTime >= :from and p.providerTime <= :to order by p.providerTime")
    List<Payment> statement(@Param("provider") PaymentProvider provider, @Param("from") Long from, @Param("to") Long to);

    /** Paynet hisoboti: berilgan oraliqda to'langan (bekor qilinmagan) tranzaksiyalar */
    @Query("select p from Payment p where p.provider = :provider and p.status = uz.yukgo.payment.PaymentStatus.PAID "
            + "and p.paidAt >= :from and p.paidAt <= :to order by p.paidAt")
    List<Payment> paidBetween(@Param("provider") PaymentProvider provider, @Param("from") Instant from, @Param("to") Instant to);
}
