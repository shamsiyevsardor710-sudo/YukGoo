package uz.yukgo.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);

    List<User> findByFirstNameLowerAndLastNameLower(String firstNameLower, String lastNameLower);

    /** Bir chat bir nechta hisobga biriktirilgan bo'lishi mumkin (raqam almashtirilganda) — shuning uchun First */
    Optional<User> findFirstByTelegramChatIdOrderByIdDesc(Long chatId);

    List<User> findByTelegramChatId(Long chatId);

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

    /**
     * viewerId — ko'rayotgan foydalanuvchi: u bilan blok bo'lgan haydovchilar chiqarilmaydi (null — mehmon).
     * bodyTypes — kuzov turi va unga tenglashadigan eski qiymatlar ({@link BodyType#withLegacy()}), anyBody=true bo'lsa
     * e'tiborga olinmaydi (ro'yxat bo'sh bo'lmasin: "in ()" — SQL xatosi).
     * homeRegion / homeDistrict — ko'rayotgan odamning manzili: avval shu tumandagi, keyin viloyatdagi haydovchilar,
     * so'ng boshqa viloyatlardagilar — viloyat markazlari orasidagi masofa bo'yicha (homeLat/homeLng).
     * Viloyati ko'rsatilmagan haydovchilar ham chiqadi (left join), masofa bo'yicha — oxirida.
     */
    @Query("""
            select u from User u left join u.region ur
            where u.status = uz.yukgo.user.UserStatus.ACTIVE and u.vehicle.brand is not null
              and (:regionId is null or u.region.id = :regionId)
              and (:anyBody = true or u.vehicle.bodyType in :bodyTypes)
              and (:viewerId is null or (
                   not exists (select b.id from UserBlock b where b.blockerId = :viewerId and b.blockedId = u.id)
                   and not exists (select b2.id from UserBlock b2 where b2.blockerId = u.id and b2.blockedId = :viewerId)))
            order by (case when u.district.id = :homeDistrict then 0 when u.region.id = :homeRegion then 1 else 2 end),
                     (case when ur.id is null then 1 else 0 end),
                     (ur.lat - :homeLat) * (ur.lat - :homeLat) + (ur.lng - :homeLng) * (ur.lng - :homeLng) * 0.57,
                     (case when u.driverVerified = true then 1 else 0 end) desc,
                     (case when u.ratingCount = 0 then 0 else u.ratingSum * 1.0 / u.ratingCount end) desc,
                     u.deliveriesCompleted desc,
                     u.id desc
            """)
    Page<User> searchDrivers(@Param("regionId") Long regionId, @Param("anyBody") boolean anyBody,
                             @Param("bodyTypes") Collection<BodyType> bodyTypes,
                             @Param("viewerId") Long viewerId, @Param("homeRegion") Long homeRegion,
                             @Param("homeDistrict") Long homeDistrict,
                             @Param("homeLat") Double homeLat, @Param("homeLng") Double homeLng, Pageable pageable);

    /**
     * Yangi e'lon haqida xabar olishi mumkin bo'lgan haydovchilar (viloyat bo'yicha): e'lon haydovchining o'z (uy)
     * viloyatidan yoki viloyatiga, yoki u tanlagan ishlaydigan viloyatlardan biridan yoki biriga.
     * Avvalgidek "hamma viloyatlar" emas: uzoqdagi e'lonlar bilan bezovta qilinmaydi.
     */
    @Query("""
            select distinct u from User u left join u.workRegionIds wr
            where u.status = uz.yukgo.user.UserStatus.ACTIVE and u.vehicle.brand is not null and u.notifyNewOrders = true
              and u.available = true and u.telegramChatId is not null and u.id <> :excludeId
              and (wr = :regionA or wr = :regionB or u.region.id = :regionA or u.region.id = :regionB)
            """)
    List<User> findDriversToNotify(@Param("regionA") Long regionA, @Param("regionB") Long regionB,
                                   @Param("excludeId") Long excludeId);

    @Query("select distinct u.telegramChatId from User u where u.telegramChatId is not null and u.status = uz.yukgo.user.UserStatus.ACTIVE")
    List<Long> findAllActiveChatIds();

    // ---- Marketing: manbalar va takliflar ----

    /**
     * Manba kodi bo'yicha: [kod, foydalanuvchilar, haydovchilar, e'lon berganlar, faollashganlar, oxirgi ro'yxatdan o'tish].
     * Faollashgan — kamida bitta buyurtmasi yakunlangan: haydovchi sifatida yetkazgan yoki yuk egasi sifatida yuki yetkazilgan.
     */
    @Query("""
            select u.signupSource, count(u),
                   sum(case when u.vehicle.brand is not null then 1 else 0 end),
                   sum(case when u.ordersCreated > 0 then 1 else 0 end),
                   sum(case when u.deliveriesCompleted > 0
                              or exists (select o.id from Shipment o
                                         where o.sender = u and o.status = uz.yukgo.order.OrderStatus.DELIVERED)
                            then 1 else 0 end),
                   max(u.createdAt)
            from User u
            where u.signupSource is not null and u.createdAt >= :since
            group by u.signupSource
            """)
    List<Object[]> sourceStats(@Param("since") Instant since);

    /** Taklif qilganlar: [taklif qilgan ID, taklif qilinganlar, bonusli (birinchi buyurtmasi yetkazilgan)] */
    @Query("""
            select u.referredById, count(u), sum(case when u.referralRewardedAt is not null then 1 else 0 end)
            from User u
            where u.referredById is not null and u.createdAt >= :since
            group by u.referredById
            order by count(u) desc
            """)
    List<Object[]> referralStats(@Param("since") Instant since, Pageable pageable);

    long countByCreatedAtGreaterThanEqual(Instant since);

    long countBySignupSourceIsNotNullAndCreatedAtGreaterThanEqual(Instant since);

    long countByReferredById(Long referrerId);

    long countBySignupSource(String signupSource);

    long countByReferredByIdAndReferralRewardedAtIsNotNull(Long referrerId);

    /** Bloklangandan keyin bazadagi haqiqiy qiymat (xotiradagi nusxa eskirgan bo'lishi mumkin) */
    @Query("select u.referralRewardedAt from User u where u.id = :id")
    Instant referralRewardedAtOf(@Param("id") Long id);

    /** Hisobni o'chirish kabi amallar uchun: qator tranzaksiya oxirigacha bloklanadi (ikki marta bosilsa ham bir marta bajariladi) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockById(@Param("id") Long id);
}
