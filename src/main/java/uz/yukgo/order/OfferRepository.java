package uz.yukgo.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OfferRepository extends JpaRepository<Offer, Long> {
    List<Offer> findByOrderIdOrderByPriceAscCreatedAtAsc(Long orderId);

    List<Offer> findByOrderIdAndStatus(Long orderId, OfferStatus status);

    Optional<Offer> findFirstByOrderIdAndDriverIdAndStatus(Long orderId, Long driverId, OfferStatus status);

    long countByOrderIdAndStatus(Long orderId, OfferStatus status);

    /** Haydovchining kutilayotgan takliflari bor buyurtmalar (faqat ID — keyin har biri bloklanadi) */
    @Query("select distinct f.order.id from Offer f where f.driver.id = :driverId and f.status = uz.yukgo.order.OfferStatus.PENDING")
    List<Long> findOrderIdsWithPendingOffer(@Param("driverId") Long driverId);

    /** Haydovchi qachondir taklif bergan (yoki olgan) barcha buyurtmalar */
    @Query("select distinct f.order.id from Offer f where f.driver.id = :driverId")
    List<Long> findOrderIdsOfferedBy(@Param("driverId") Long driverId);

    /**
     * a va b o'rtasidagi ochiq takliflar bor e'lonlar: biri yuboruvchi, ikkinchisi taklif bergan haydovchi
     * (faqat ID — keyin har biri bloklanadi; takrorlanishi mumkin)
     */
    @Query("""
            select f.order.id from Offer f
            where f.status = uz.yukgo.order.OfferStatus.PENDING and f.order.status = uz.yukgo.order.OrderStatus.NEW
              and ((f.driver.id = :a and f.order.sender.id = :b) or (f.driver.id = :b and f.order.sender.id = :a))
            """)
    List<Long> findOpenOrderIdsBetween(@Param("a") Long a, @Param("b") Long b);
}
