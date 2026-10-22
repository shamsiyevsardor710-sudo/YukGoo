package uz.yukgo.driver;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface DriverVerificationFileRepository extends JpaRepository<DriverVerificationFile, Long> {
    Optional<DriverVerificationFile> findFirstByVerificationIdAndKind(Long verificationId, String kind);

    /** Rasmlar baytlarini xotiraga yuklamasdan o'chiradi */
    @Modifying(flushAutomatically = true)
    @Query("delete from DriverVerificationFile f where f.verificationId in :ids")
    int deleteByVerificationIds(@Param("ids") Collection<Long> ids);
}
