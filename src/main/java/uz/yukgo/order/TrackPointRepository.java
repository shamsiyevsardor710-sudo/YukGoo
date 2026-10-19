package uz.yukgo.order;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrackPointRepository extends JpaRepository<TrackPoint, Long> {
    List<TrackPoint> findByOrderIdOrderByCreatedAtDesc(Long orderId, Pageable pageable);
}
