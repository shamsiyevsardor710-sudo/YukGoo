package uz.yukgo.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.yukgo.user.BodyType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Holatni o'zgartiruvchi amallar uchun: qator tranzaksiya oxirigacha bloklanadi (bir vaqtda ikki "olish" bo'lmaydi). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Shipment o where o.id = :id")
    Optional<Order> lockById(@Param("id") Long id);

    /**
     * Ochiq e'lonlar bozori.
     * Tartib: ko'rayotgan odamning uyiga yaqinligi — avval o'z tumanidan (homeDistrict), keyin o'z viloyatidan
     * (homeRegion), so'ng boshqa viloyatlardan — viloyat markazlari orasidagi masofa bo'yicha (homeLat/homeLng);
     * har bir joy ichida: shoshilinchlar; olib ketish sanasi bugun va undan keyingilar — eng yaqini birinchi;
     * so'ng sanasi o'tib ketganlar; sanasiz e'lonlar eng oxirida. Bir kunning ichida — eng yangi e'lon birinchi.
     * Mehmon (home* = null) uchun joy bo'yicha tartib yo'q — faqat shoshilinch va sana.
     * {@code today} — Toshkent vaqti bo'yicha bugungi sana (server UTC'da ishlaydi, current_date tunda bir kun orqada qoladi).
     * {@code viewerId} — ko'rayotgan foydalanuvchi: u bilan blok bo'lgan yuboruvchilarning e'lonlari chiqarilmaydi (null — mehmon).
     * Tuman bo'yicha filtr: shu tumandagi va tumani ko'rsatilmagan (butun viloyat) e'lonlar.
     * {@code anyBody} — kuzov turi bo'yicha filtr yo'q; aks holda bodyTypes (eski qiymatlari bilan) yoki kuzovi farqi yo'q.
     */
    @Query(value = """
            select o from Shipment o
            where o.status = uz.yukgo.order.OrderStatus.NEW
              and (:kind is null or o.kind = :kind)
              and (:fromRegion is null or o.fromRegion.id = :fromRegion)
              and (:fromDistrict is null or o.fromDistrict is null or o.fromDistrict.id = :fromDistrict)
              and (:toRegion is null or o.toRegion.id = :toRegion)
              and (:toDistrict is null or o.toDistrict is null or o.toDistrict.id = :toDistrict)
              and (:cargoType is null or o.cargoType = :cargoType)
              and (:scope is null or o.scope = :scope)
              and (:anyBody = true or o.requiredBodyType is null or o.requiredBodyType in :bodyTypes)
              and (:maxWeight is null or o.weightKg is null or o.weightKg <= :maxWeight)
              and (:q is null or lower(o.title) like :q or lower(o.description) like :q)
              and (:viewerId is null or (
                   not exists (select b.id from UserBlock b where b.blockerId = :viewerId and b.blockedId = o.sender.id)
                   and not exists (select b2.id from UserBlock b2 where b2.blockerId = o.sender.id and b2.blockedId = :viewerId)))
            order by case when o.fromDistrict.id = :homeDistrict then 0
                          when o.fromRegion.id = :homeRegion then 1 else 2 end,
                     (o.fromRegion.lat - :homeLat) * (o.fromRegion.lat - :homeLat)
                       + (o.fromRegion.lng - :homeLng) * (o.fromRegion.lng - :homeLng) * 0.57,
                     o.urgent desc,
                     case when o.pickupDate is null then 1 else 0 end,
                     case when o.pickupDate < :today then 1 else 0 end,
                     o.pickupDate asc,
                     o.createdAt desc,
                     o.id desc
            """,
            countQuery = """
            select count(o) from Shipment o
            where o.status = uz.yukgo.order.OrderStatus.NEW
              and (:kind is null or o.kind = :kind)
              and (:fromRegion is null or o.fromRegion.id = :fromRegion)
              and (:fromDistrict is null or o.fromDistrict is null or o.fromDistrict.id = :fromDistrict)
              and (:toRegion is null or o.toRegion.id = :toRegion)
              and (:toDistrict is null or o.toDistrict is null or o.toDistrict.id = :toDistrict)
              and (:cargoType is null or o.cargoType = :cargoType)
              and (:scope is null or o.scope = :scope)
              and (:anyBody = true or o.requiredBodyType is null or o.requiredBodyType in :bodyTypes)
              and (:maxWeight is null or o.weightKg is null or o.weightKg <= :maxWeight)
              and (:q is null or lower(o.title) like :q or lower(o.description) like :q)
              and (:viewerId is null or (
                   not exists (select b.id from UserBlock b where b.blockerId = :viewerId and b.blockedId = o.sender.id)
                   and not exists (select b2.id from UserBlock b2 where b2.blockerId = o.sender.id and b2.blockedId = :viewerId)))
            """)
    Page<Order> market(@Param("kind") OrderKind kind,
                       @Param("fromRegion") Long fromRegion, @Param("fromDistrict") Long fromDistrict,
                       @Param("toRegion") Long toRegion, @Param("toDistrict") Long toDistrict,
                       @Param("cargoType") CargoType cargoType, @Param("scope") DeliveryScope scope,
                       @Param("anyBody") boolean anyBody, @Param("bodyTypes") Collection<BodyType> bodyTypes,
                       @Param("maxWeight") Double maxWeight,
                       @Param("q") String q, @Param("today") LocalDate today,
                       @Param("viewerId") Long viewerId,
                       @Param("homeRegion") Long homeRegion, @Param("homeDistrict") Long homeDistrict,
                       @Param("homeLat") Double homeLat, @Param("homeLng") Double homeLng,
                       Pageable pageable);

    /**
     * Yangi reysga mos ochiq e'lonlar (e'lon egalariga «yo'nalishingizda haydovchi bor» xabari uchun):
     * shu viloyatdan shu viloyatga, sanasi o'tmagan yoki ko'rsatilmagan. Qolgan shartlar (sana oralig'i, tur, joylar
     * soni, blok) — MatchService'da.
     */
    @Query("""
            select o from Shipment o join fetch o.sender s
            where o.status = uz.yukgo.order.OrderStatus.NEW
              and o.fromRegion.id = :fromRegion and o.toRegion.id = :toRegion
              and s.id <> :driverId and s.status = uz.yukgo.user.UserStatus.ACTIVE
              and (o.pickupDate is null or o.pickupDate >= :today)
            order by o.createdAt desc
            """)
    List<Order> findOpenOnRoute(@Param("fromRegion") Long fromRegion, @Param("toRegion") Long toRegion,
                                @Param("driverId") Long driverId, @Param("today") LocalDate today, Pageable pageable);

    @Query("""
            select o from Shipment o where o.sender.id = :userId
              and o.status in :statuses
            order by o.createdAt desc
            """)
    Page<Order> findBySender(@Param("userId") Long userId, @Param("statuses") Collection<OrderStatus> statuses,
                             Pageable pageable);

    @Query("""
            select o from Shipment o where o.driver.id = :userId
              and o.status in :statuses
            order by o.createdAt desc
            """)
    Page<Order> findByDriver(@Param("userId") Long userId, @Param("statuses") Collection<OrderStatus> statuses,
                             Pageable pageable);

    /** Haydovchi taklif yuborgan, hali hal bo'lmagan buyurtmalar */
    @Query("""
            select distinct o from Offer f join f.order o
            where f.driver.id = :userId and f.status = uz.yukgo.order.OfferStatus.PENDING
              and o.status = uz.yukgo.order.OrderStatus.NEW
            order by o.createdAt desc
            """)
    Page<Order> findWithMyPendingOffer(@Param("userId") Long userId, Pageable pageable);

    @Query("""
            select o from Shipment o
            where (o.sender.id = :userId or o.driver.id = :userId) and o.status in :statuses
            order by o.createdAt desc
            """)
    List<Order> findActiveForUser(@Param("userId") Long userId, @Param("statuses") Collection<OrderStatus> statuses);

    /** Ikki foydalanuvchi o'rtasida haydovchiga berilgan buyurtma soni (bor bo'lsa — telefonlari bir-biriga ochiq) */
    @Query("""
            select count(o) from Shipment o
            where o.status in :statuses
              and ((o.sender.id = :a and o.driver.id = :b) or (o.sender.id = :b and o.driver.id = :a))
            """)
    long countDealsBetween(@Param("a") Long a, @Param("b") Long b, @Param("statuses") Collection<OrderStatus> statuses);

    /** Hisob o'chirilganda: foydalanuvchining hali haydovchi kutayotgan e'lonlari (faqat ID — keyin har biri bloklanadi) */
    @Query("select o.id from Shipment o where o.sender.id = :userId and o.status = uz.yukgo.order.OrderStatus.NEW")
    List<Long> findOpenOrderIdsBySender(@Param("userId") Long userId);

    @Query("""
            select count(o) from Shipment o
            where (o.sender.id = :userId or o.driver.id = :userId) and o.status in :statuses
            """)
    long countForUser(@Param("userId") Long userId, @Param("statuses") Collection<OrderStatus> statuses);

    /** Hisob o'chirilganda: haydovchining buyurtmalardagi oxirgi joylashuvi o'chiriladi */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Shipment o set o.driverLat = null, o.driverLng = null, o.driverLocationAt = null
            where o.id in (select x.id from Shipment x where x.driver.id = :driverId)
            """)
    int clearDriverLocation(@Param("driverId") Long driverId);

    long countByStatus(OrderStatus status);

    /** Haydovchida hozir nechta buyurtma bor (yangi haydovchi bonusi hisobi uchun) */
    long countByDriverIdAndStatusIn(Long driverId, Collection<OrderStatus> statuses);

    /** Foydalanuvchi avval shoshilinch e'lon bergan(mi) — "birinchi shoshilinch bepul" uchun */
    boolean existsBySenderIdAndUrgentTrueAndIdNot(Long senderId, Long excludeOrderId);

    /** Taklif bonusi: haydovchi sifatida yakunlangan buyurtmalar (taklif qilgan odam bilan qilinganlari hisobga olinmaydi) */
    long countByDriverIdAndStatusAndSenderIdNot(Long driverId, OrderStatus status, Long excludeSenderId);

    /** Taklif bonusi: yuk egasi sifatida yakunlangan buyurtmalar (taklif qilgan odam bilan qilinganlari hisobga olinmaydi) */
    long countBySenderIdAndStatusAndDriverIdNot(Long senderId, OrderStatus status, Long excludeDriverId);

    long countByCreatedAtAfter(Instant after);

    @Query("select coalesce(sum(o.finalPrice), 0) from Shipment o where o.status = uz.yukgo.order.OrderStatus.DELIVERED")
    long sumDeliveredValue();

    @Query("""
            select o from Shipment o
            where (:status is null or o.status = :status)
              and (:q is null or lower(o.title) like :q or o.sender.phone like :q)
            order by o.createdAt desc
            """)
    Page<Order> adminSearch(@Param("status") OrderStatus status, @Param("q") String q, Pageable pageable);

    /** Eskirgan ochiq buyurtmalarni topish (faqat tungi avtomatik yopish uchun — qatorlar bloklanadi) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant before);

    /** Olib ketish sanasidan ancha o'tgan ochiq buyurtmalar (faqat tungi avtomatik yopish uchun — qatorlar bloklanadi) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Order> findByStatusAndPickupDateBefore(OrderStatus status, LocalDate before);
}
