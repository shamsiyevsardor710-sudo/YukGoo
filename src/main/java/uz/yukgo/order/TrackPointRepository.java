package uz.yukgo.order;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TrackPointRepository extends JpaRepository<TrackPoint, Long> {
    List<TrackPoint> findByOrderIdOrderByCreatedAtDesc(Long orderId, Pageable pageable);

    /** Haydovchi voz kechganda: uning shu buyurtmadagi yo'li (keyingi haydovchiga va yuboruvchiga kerak emas) */
    @Modifying(flushAutomatically = true)
    @Query("delete from TrackPoint t where t.orderId = :orderId")
    int deleteByOrder(@Param("orderId") Long orderId);

    /** Hisob o'chirilganda: haydovchi bo'lgan buyurtmalardagi butun GPS tarixi */
    @Modifying(flushAutomatically = true)
    @Query("delete from TrackPoint t where t.orderId in (select o.id from Shipment o where o.driver.id = :driverId)")
    int deleteByDriver(@Param("driverId") Long driverId);
}
