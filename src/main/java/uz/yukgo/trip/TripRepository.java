package uz.yukgo.trip;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TripRepository extends JpaRepository<Trip, Long> {
    /**
     * Reyslar ro'yxati. viewerId — ko'rayotgan foydalanuvchi: u bilan blok bo'lgan haydovchilarning reyslari
     * chiqarilmaydi (null — mehmon). «Har kuni» reyslar jo'nash sanasidan qat'i nazar ko'rinadi.
     * Tuman tanlansa — shu tumandan (yoki tumani ko'rsatilmagan) reyslar.
     * Tartib: avval ko'rayotgan odamning tumanidan, keyin viloyatidan, so'ng yaqin viloyatlardan; har birida — eng yaqin jo'nash.
     */
    @Query(value = """
            select t from Trip t
            where t.active = true and (t.daily = true or t.departAt >= :since)
              and t.driver.status = uz.yukgo.user.UserStatus.ACTIVE
              and (:fromRegion is null or t.fromRegion.id = :fromRegion)
              and (:fromDistrict is null or t.fromDistrict is null or t.fromDistrict.id = :fromDistrict)
              and (:toRegion is null or t.toRegion.id = :toRegion)
              and (:toDistrict is null or t.toDistrict is null or t.toDistrict.id = :toDistrict)
              and (:viewerId is null or (
                   not exists (select b.id from UserBlock b where b.blockerId = :viewerId and b.blockedId = t.driver.id)
                   and not exists (select b2.id from UserBlock b2 where b2.blockerId = t.driver.id and b2.blockedId = :viewerId)))
            order by case when t.fromDistrict.id = :homeDistrict then 0
                          when t.fromRegion.id = :homeRegion then 1 else 2 end,
                     (t.fromRegion.lat - :homeLat) * (t.fromRegion.lat - :homeLat)
                       + (t.fromRegion.lng - :homeLng) * (t.fromRegion.lng - :homeLng) * 0.57,
                     t.departAt asc,
                     t.id desc
            """,
            countQuery = """
            select count(t) from Trip t
            where t.active = true and (t.daily = true or t.departAt >= :since)
              and t.driver.status = uz.yukgo.user.UserStatus.ACTIVE
              and (:fromRegion is null or t.fromRegion.id = :fromRegion)
              and (:fromDistrict is null or t.fromDistrict is null or t.fromDistrict.id = :fromDistrict)
              and (:toRegion is null or t.toRegion.id = :toRegion)
              and (:toDistrict is null or t.toDistrict is null or t.toDistrict.id = :toDistrict)
              and (:viewerId is null or (
                   not exists (select b.id from UserBlock b where b.blockerId = :viewerId and b.blockedId = t.driver.id)
                   and not exists (select b2.id from UserBlock b2 where b2.blockerId = t.driver.id and b2.blockedId = :viewerId)))
            """)
    Page<Trip> search(@Param("fromRegion") Long fromRegion, @Param("fromDistrict") Long fromDistrict,
                      @Param("toRegion") Long toRegion, @Param("toDistrict") Long toDistrict,
                      @Param("since") LocalDateTime since, @Param("viewerId") Long viewerId,
                      @Param("homeRegion") Long homeRegion, @Param("homeDistrict") Long homeDistrict,
                      @Param("homeLat") Double homeLat, @Param("homeLng") Double homeLng,
                      Pageable pageable);

    List<Trip> findByDriverIdOrderByDepartAtDesc(Long driverId);

    /**
     * Yangi e'longa mos reyslar: shu viloyatdan shu viloyatga, faol, «har kuni» yoki [fromTime, toTime) oralig'ida jo'naydigan.
     * Tuman, tur, joylar soni va blok — MatchService'da tekshiriladi.
     */
    @Query("""
            select t from Trip t join fetch t.driver d
            where t.active = true
              and t.fromRegion.id = :fromRegion and t.toRegion.id = :toRegion
              and d.id <> :senderId and d.status = uz.yukgo.user.UserStatus.ACTIVE and d.vehicle.brand is not null
              and (t.daily = true or (t.departAt >= :fromTime and t.departAt < :toTime))
            order by t.departAt asc
            """)
    List<Trip> findForOrder(@Param("fromRegion") Long fromRegion, @Param("toRegion") Long toRegion,
                            @Param("senderId") Long senderId, @Param("fromTime") LocalDateTime fromTime,
                            @Param("toTime") LocalDateTime toTime, Pageable pageable);
}
