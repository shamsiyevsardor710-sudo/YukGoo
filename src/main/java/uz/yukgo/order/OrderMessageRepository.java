package uz.yukgo.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderMessageRepository extends JpaRepository<OrderMessage, Long> {
    List<OrderMessage> findByOrderIdAndIdGreaterThanOrderByIdAsc(Long orderId, Long afterId);
}
