package uz.yukgo.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    boolean existsByOrderIdAndAuthorId(Long orderId, Long authorId);

    Page<Review> findByTargetIdOrderByCreatedAtDesc(Long targetId, Pageable pageable);

    List<Review> findByOrderId(Long orderId);

    @Query("select r.stars, count(r) from Review r where r.target.id = :targetId group by r.stars")
    List<Object[]> starDistribution(Long targetId);
}
