package uz.yukgo.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.yukgo.user.BodyType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Ochiq buyurtmalar bozori (haydovchilar uchun) */
    @Query("""
            select o from Shipment o
            where o.status = uz.yukgo.order.OrderStatus.NEW
              and (:fromRegion is null or o.fromRegion.id = :fromRegion)
              and (:toRegion is null or o.toRegion.id = :toRegion)
              and (:cargoType is null or o.cargoType = :cargoType)
              and (:scope is null or o.scope = :scope)
              and (:bodyType is null or o.requiredBodyType is null or o.requiredBodyType = :bodyType)
              and (:maxWeight is null or o.weightKg is null or o.weightKg <= :maxWeight)
              and (:q is null or lower(o.title) like :q or lower(o.description) like :q)
            order by o.urgent desc, o.createdAt desc
            """)
    Page<Order> market(@Param("fromRegion") Long fromRegion, @Param("toRegion") Long toRegion,
                       @Param("cargoType") CargoType cargoType, @Param("scope") DeliveryScope scope,
                       @Param("bodyType") BodyType bodyType, @Param("maxWeight") Double maxWeight,
                       @Param("q") String q, Pageable pageable);

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

    long countByStatus(OrderStatus status);

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

    /** Eskirgan ochiq buyurtmalarni topish (avtomatik yopish uchun) */
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant before);
}
