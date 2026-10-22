package uz.yukgo.driver;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DriverVerificationRepository extends JpaRepository<DriverVerification, Long> {
    Optional<DriverVerification> findFirstByUserIdOrderByIdDesc(Long userId);

    List<DriverVerification> findByUserId(Long userId);

    List<DriverVerification> findByUserIdAndStatus(Long userId, String status);

    List<DriverVerification> findByStatusOrderBySubmittedAtAsc(String status);

    List<DriverVerification> findTop30ByStatusNotOrderByDecidedAtDesc(String status);

    long countByStatus(String status);

    /** Ikki admin bir vaqtda qaror qilmasin */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from DriverVerification v where v.id = :id")
    Optional<DriverVerification> lockById(@Param("id") Long id);
}
