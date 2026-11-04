package uz.yukgo.push;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PushTokenRepository extends JpaRepository<PushToken, Long> {
    List<PushToken> findByUserIdOrderByUpdatedAtDesc(Long userId);

    List<PushToken> findByToken(String token);

    /** Hisob o'chirilganda */
    @Modifying(flushAutomatically = true)
    @Query("delete from PushToken t where t.userId = :userId")
    int deleteAllForUser(@Param("userId") Long userId);
}
