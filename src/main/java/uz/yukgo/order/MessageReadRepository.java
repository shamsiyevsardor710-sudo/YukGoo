package uz.yukgo.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MessageReadRepository extends JpaRepository<MessageRead, Long> {
    Optional<MessageRead> findFirstByUserIdAndOrderIdAndDriverIdOrderByIdAsc(Long userId, Long orderId, Long driverId);

    /** Hisob o'chirilganda */
    @Modifying(flushAutomatically = true)
    @Query("delete from MessageRead r where r.userId = :userId")
    int deleteAllForUser(@Param("userId") Long userId);
}
