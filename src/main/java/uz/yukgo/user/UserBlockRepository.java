package uz.yukgo.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {
    boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    Optional<UserBlock> findByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    List<UserBlock> findByBlockerIdOrderByCreatedAtDesc(Long blockerId);

    long countByBlockerId(Long blockerId);

    /** Shu foydalanuvchi bloklaganlar */
    @Query("select b.blockedId from UserBlock b where b.blockerId = :userId")
    List<Long> blockedBy(@Param("userId") Long userId);

    /** Shu foydalanuvchini bloklaganlar */
    @Query("select b.blockerId from UserBlock b where b.blockedId = :userId")
    List<Long> blockersOf(@Param("userId") Long userId);

    /** Hisob o'chirilganda: u bloklaganlar ham, uni bloklaganlar ham */
    @Modifying
    @Query("delete from UserBlock b where b.blockerId = :userId or b.blockedId = :userId")
    int deleteAllFor(@Param("userId") Long userId);
}
