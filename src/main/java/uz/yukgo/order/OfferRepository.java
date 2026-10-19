package uz.yukgo.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OfferRepository extends JpaRepository<Offer, Long> {
    List<Offer> findByOrderIdOrderByPriceAscCreatedAtAsc(Long orderId);

    List<Offer> findByOrderIdAndStatus(Long orderId, OfferStatus status);

    Optional<Offer> findFirstByOrderIdAndDriverIdAndStatus(Long orderId, Long driverId, OfferStatus status);

    long countByOrderIdAndStatus(Long orderId, OfferStatus status);
}
