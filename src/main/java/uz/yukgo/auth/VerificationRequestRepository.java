package uz.yukgo.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, String> {
    Optional<VerificationRequest> findByLinkToken(String linkToken);

    List<VerificationRequest> findByPhoneAndConsumedFalseAndVerifiedFalseAndCreatedAtAfterOrderByCreatedAtDesc(
            String phone, Instant after);

    long countByPhoneAndCreatedAtAfter(String phone, Instant after);

    /** Eskirgan tasdiqlash so'rovlari (kod 5-10 daqiqa, so'rov 30 daqiqa amal qiladi — keyin kerak emas) */
    @Modifying
    @Query("delete from VerificationRequest v where v.createdAt < :before")
    int deleteCreatedBefore(@Param("before") Instant before);

    /** Hisob o'chirilganda: shu raqam va shu foydalanuvchiga tegishli tasdiqlash so'rovlari */
    @Modifying(flushAutomatically = true)
    @Query("delete from VerificationRequest v where v.phone = :phone or v.userId = :userId")
    int deleteForAccount(@Param("phone") String phone, @Param("userId") Long userId);
}
