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

    // «Xabarlar». Men ikki xil qatnashaman: haydovchi sifatida (ip — meniki) va e'lon egasi sifatida (e'lon — meniki).
    // So'rovlar ataylab ikkiga bo'lingan: har biri o'z indeksidan foydalanadi (idx_msg_driver va idx_orders_sender);
    // "… or … in (…)" ko'rinishida PostgreSQL butun jadvalni o'qib chiqardi.

    /** Haydovchi sifatidagi yozishmalarim — [orderId, driverId, oxirgi xabar ID], eng yangisi birinchi */
    @Query("""
            select m.orderId, m.driverId, max(m.id) from OrderMessage m
            where m.driverId = :me
            group by m.orderId, m.driverId
            order by max(m.id) desc
            """)
    List<Object[]> threadsAsDriver(@Param("me") Long me, org.springframework.data.domain.Pageable page);

    /** E'lonlarim bo'yicha yozishmalar (har bir haydovchi bilan alohida) — [orderId, driverId, oxirgi xabar ID] */
    @Query("""
            select m.orderId, m.driverId, max(m.id) from OrderMessage m
            where m.driverId is not null and m.orderId in (select o.id from Shipment o where o.sender.id = :me)
            group by m.orderId, m.driverId
            order by max(m.id) desc
            """)
    List<Object[]> threadsAsSender(@Param("me") Long me, org.springframework.data.domain.Pageable page);

    /** Haydovchi sifatida: men o'qimagan (e'lon egasi yozgan) xabarlar — [orderId, driverId, soni, oxirgisining ID] */
    @Query("""
            select m.orderId, m.driverId, count(m), max(m.id) from OrderMessage m
            where m.driverId = :me and m.author.id <> :me
              and m.id > coalesce((select max(r.lastReadId) from MessageRead r
                                   where r.userId = :me and r.orderId = m.orderId and r.driverId = m.driverId), 0)
            group by m.orderId, m.driverId
            """)
    List<Object[]> unreadAsDriver(@Param("me") Long me);

    /** E'lon egasi sifatida: men o'qimagan (haydovchilar yozgan) xabarlar — [orderId, driverId, soni, oxirgisining ID] */
    @Query("""
            select m.orderId, m.driverId, count(m), max(m.id) from OrderMessage m
            where m.driverId is not null and m.author.id <> :me
              and m.orderId in (select o.id from Shipment o where o.sender.id = :me)
              and m.id > coalesce((select max(r.lastReadId) from MessageRead r
                                   where r.userId = :me and r.orderId = m.orderId and r.driverId = m.driverId), 0)
            group by m.orderId, m.driverId
            """)
    List<Object[]> unreadAsSender(@Param("me") Long me);

    /** Haydovchi shu e'lon egasiga yozganmi (e'lon egasi faqat o'ziga yozgan haydovchiga javob bera oladi) */
    boolean existsByOrderIdAndDriverId(Long orderId, Long driverId);

}
