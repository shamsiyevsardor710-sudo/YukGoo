package uz.yukgo.trip;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TripRepository extends JpaRepository<Trip, Long> {
    @Query("""
            select t from Trip t
            where t.active = true and t.departAt >= :since
              and t.driver.status = uz.yukgo.user.UserStatus.ACTIVE
              and (:fromRegion is null or t.fromRegion.id = :fromRegion)
              and (:toRegion is null or t.toRegion.id = :toRegion)
              and (:minKg is null or t.availableKg is null or t.availableKg >= :minKg)
            order by t.departAt asc
            """)
    Page<Trip> search(@Param("fromRegion") Long fromRegion, @Param("toRegion") Long toRegion,
                      @Param("minKg") Integer minKg, @Param("since") LocalDateTime since, Pageable pageable);

    List<Trip> findByDriverIdOrderByDepartAtDesc(Long driverId);
}
