package uz.yukgo.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderMessageRepository extends JpaRepository<OrderMessage, Long> {
    List<OrderMessage> findByOrderIdAndIdGreaterThanOrderByIdAsc(Long orderId, Long afterId);

    /**
     * Bitta yozishma ipi: e'lon egasi ↔ shu haydovchi. Iplar paydo bo'lishidan oldingi xabarlar ishga tushishda
     * egasiga biriktiriladi (SchemaMaintenance); kimniki ekani aniqlanmaganlari hech kimga ko'rsatilmaydi.
     */
    @Query("""
            select m from OrderMessage m join fetch m.author
            where m.orderId = :orderId and m.id > :afterId and m.driverId = :driverId
            order by m.id asc
            """)
    List<OrderMessage> thread(@Param("orderId") Long orderId, @Param("driverId") Long driverId, @Param("afterId") long afterId);

    /** Haydovchi shu e'lon egasiga yozganmi (e'lon egasi faqat o'ziga yozgan haydovchiga javob bera oladi) */
    boolean existsByOrderIdAndDriverId(Long orderId, Long driverId);

}
