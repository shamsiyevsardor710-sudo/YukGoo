package uz.yukgo.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {
    List<OrderEvent> findByOrderIdOrderByCreatedAtAsc(Long orderId);

    List<OrderEvent> findByOrderIdIn(Collection<Long> orderIds);

    /** Hisob o'chirilganda: tarixdagi "kim qildi" ismi almashtiriladi */
    @Modifying(flushAutomatically = true)
    @Query("update OrderEvent e set e.actorName = :name where e.actorId = :actorId")
    int renameActor(@Param("actorId") Long actorId, @Param("name") String name);
}
