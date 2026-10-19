package uz.yukgo.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);

    List<User> findByFirstNameLowerAndLastNameLower(String firstNameLower, String lastNameLower);

    /** Bir chat bir nechta hisobga biriktirilgan bo'lishi mumkin (raqam almashtirilganda) — shuning uchun First */
    Optional<User> findFirstByTelegramChatIdOrderByIdDesc(Long chatId);

    boolean existsByRole(Role role);

    long countByStatus(UserStatus status);

    long countByRole(Role role);

    @Query("select count(u) from User u where u.vehicle.brand is not null and u.status = uz.yukgo.user.UserStatus.ACTIVE")
    long countDrivers();

    @Query("""
            select u from User u
            where (:q is null or lower(u.firstName) like :q or lower(u.lastName) like :q or u.phone like :q)
              and (:role is null or u.role = :role)
              and (:status is null or u.status = :status)
            order by u.createdAt desc
            """)
    Page<User> search(@Param("q") String q, @Param("role") Role role,
                      @Param("status") UserStatus status, Pageable pageable);

    @Query("""
            select u from User u
            where u.status = uz.yukgo.user.UserStatus.ACTIVE and u.vehicle.brand is not null
              and (:regionId is null or u.region.id = :regionId)
              and (:bodyType is null or u.vehicle.bodyType = :bodyType)
              and (:minCapacity is null or u.vehicle.capacityKg >= :minCapacity)
            order by (case when u.ratingCount = 0 then 0 else u.ratingSum * 1.0 / u.ratingCount end) desc,
                     u.deliveriesCompleted desc
            """)
    Page<User> searchDrivers(@Param("regionId") Long regionId, @Param("bodyType") BodyType bodyType,
                             @Param("minCapacity") Integer minCapacity, Pageable pageable);

    /** Yangi buyurtma haqida xabar olishi kerak bo'lgan haydovchilar */
    @Query("""
            select distinct u from User u left join u.workRegionIds wr
            where u.status = uz.yukgo.user.UserStatus.ACTIVE and u.vehicle.brand is not null and u.notifyNewOrders = true
              and u.available = true and u.telegramChatId is not null and u.id <> :excludeId
              and (wr is null or wr = :regionA or wr = :regionB)
            """)
    List<User> findDriversToNotify(@Param("regionA") Long regionA, @Param("regionB") Long regionB,
                                   @Param("excludeId") Long excludeId);

    @Query("select distinct u.telegramChatId from User u where u.telegramChatId is not null and u.status = uz.yukgo.user.UserStatus.ACTIVE")
    List<Long> findAllActiveChatIds();
}
