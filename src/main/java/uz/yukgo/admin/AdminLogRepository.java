package uz.yukgo.admin;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminLogRepository extends JpaRepository<AdminLog, Long> {
    Page<AdminLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Hisob o'chirilganda: jurnalda shu foydalanuvchi ustidan bajarilgan amallardagi ismi almashtiriladi */
    @Modifying(flushAutomatically = true)
    @Query("update AdminLog l set l.targetName = :name where l.targetUserId = :userId")
    int renameTarget(@Param("userId") Long userId, @Param("name") String name);
}
