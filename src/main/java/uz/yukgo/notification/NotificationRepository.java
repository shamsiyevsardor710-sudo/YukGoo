package uz.yukgo.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Page<Notification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByUserIdAndReadFalse(Long userId);

    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :userId and n.read = false")
    int markAllRead(Long userId);

    /** Hisob o'chirilganda: boshqalarga kelgan, ichida uning telefon raqami bor bildirishnomalar */
    List<Notification> findByBodyContaining(String fragment);

    /** Hisob o'chirilganda: foydalanuvchining barcha bildirishnomalari (ichida boshqalarning ismi va telefoni bor) */
    @Modifying(flushAutomatically = true)
    @Query("delete from Notification n where n.userId = :userId")
    int deleteAllForUser(@Param("userId") Long userId);
}
