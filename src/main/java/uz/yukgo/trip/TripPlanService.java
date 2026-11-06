package uz.yukgo.trip;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.common.ApiException;
import uz.yukgo.order.Order;
import uz.yukgo.order.OrderDtos.OrderDto;
import uz.yukgo.order.OrderKind;
import uz.yukgo.order.OrderRepository;
import uz.yukgo.order.OrderRules;
import uz.yukgo.order.OrderService;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.user.BlockService;
import uz.yukgo.user.User;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * «Reysga yig'ish» sahifasi va «Mening reyslarim»dagi qisqa xulosa: reysga mos yo'lovchilar (bo'sh o'rinlargacha) va
 * 2 tagacha pochta. Hisob-kitob — {@link TripPlanner}, bu yerda faqat ma'lumot yig'iladi.
 */
@Service
@RequiredArgsConstructor
public class TripPlanService {
    static final ZoneId TZ = ZoneId.of("Asia/Tashkent");
    /** Bir yo'nalishdagi ochiq e'lonlardan ko'pi bilan shunchasi ko'riladi */
    static final int OPEN_LIMIT = 300;

    private final TripRepository tripRepository;
    private final OrderRepository orderRepository;
    private final BlockService blocks;
    private final OrderRules rules;
    private final CurrentUser currentUser;
    private final OrderService orderService;

    /** «Mening reyslarim»dagi qator uchun: nechta yo'lovchi (necha kishi), nechta pochta mos va nechta o'rin bo'sh qoladi */
    public record PlanSummary(LocalDate day, int passengers, int people, int parcels, int freeSeats, int more) {
        static PlanSummary of(TripPlanner.Plan p) {
            return new PlanSummary(p.day(), p.passengers().size(), p.seatsFilled(), p.parcels().size(), p.freeSeats(),
                    p.morePassengers() + p.moreParcels());
        }
    }

    public record PlanDto(Long tripId, Long fromRegionId, String fromRegion, Long fromDistrictId, String fromDistrict,
                          Long toRegionId, String toRegion, Long toDistrictId, String toDistrict, boolean daily,
                          LocalDateTime departAt, int capacity, boolean capacityGuessed, int seatsInCar, int seatsFilled,
                          int freeSeats, List<OrderDto> inCar, List<OrderDto> passengers, List<OrderDto> parcels,
                          int parcelCap, long parcelsActive, int parcelSlots, int morePassengers, int moreParcels,
                          boolean past) {}

    /** Reys egasiga: reysga mos to'plam. Reys o'tib ketgan yoki yopilgan bo'lsa — past=true, ro'yxatlar bo'sh */
    @Transactional(readOnly = true)
    public PlanDto plan(Long tripId) {
        User me = currentUser.get();
        Trip t = tripRepository.findById(tripId).orElseThrow(() -> ApiException.notFound("Reys topilmadi"));
        if (!t.getDriver().getId().equals(me.getId())) throw ApiException.forbidden("Bu sizning reysingiz emas");
        LocalDateTime now = LocalDateTime.now(TZ);
        List<Order> active = orderRepository.findByDriverIdAndStatusIn(me.getId(), OrderRules.ACTIVE);
        int cap = rules.parcelCap();
        long parcelsActive = active.stream().filter(o -> o.getKind() == OrderKind.PARCEL).count();
        int slots = OrderRules.parcelSlots(cap, parcelsActive);
        TripPlanner.Plan p = TripPlanner.plan(t, me, now, Instant.now(), open(t, me, now), blocks.related(me.getId()),
                active, slots);
        if (p == null) {
            return new PlanDto(t.getId(), t.getFromRegion().getId(), t.getFromRegion().getName(), districtId(t.getFromDistrict()),
                    districtName(t.getFromDistrict()), t.getToRegion().getId(), t.getToRegion().getName(),
                    districtId(t.getToDistrict()), districtName(t.getToDistrict()), t.isDaily(), t.getDepartAt(),
                    0, t.getSeats() == null, 0, 0, 0, List.of(), List.of(), List.of(), cap, parcelsActive,
                    slots == Integer.MAX_VALUE ? -1 : slots, 0, 0, true);
        }
        return new PlanDto(t.getId(), t.getFromRegion().getId(), t.getFromRegion().getName(), districtId(t.getFromDistrict()),
                districtName(t.getFromDistrict()), t.getToRegion().getId(), t.getToRegion().getName(),
                districtId(t.getToDistrict()), districtName(t.getToDistrict()), t.isDaily(), p.departAt(),
                p.capacity(), p.capacityGuessed(), p.seatsInCar(), p.seatsFilled(), p.freeSeats(),
                orderService.listDtos(p.inCar(), me), orderService.listDtos(p.passengers(), me),
                orderService.listDtos(p.parcels(), me), cap, parcelsActive, slots == Integer.MAX_VALUE ? -1 : slots,
                p.morePassengers(), p.moreParcels(), false);
    }

    /** «Mening reyslarim»: faol reyslar uchun qisqa xulosa (reys ID → xulosa). Har bir yo'nalishga bitta so'rov */
    @Transactional(readOnly = true)
    public Map<Long, PlanSummary> summaries(User me, List<Trip> trips) {
        Map<Long, PlanSummary> out = new HashMap<>();
        if (trips.isEmpty() || !me.isDriver()) return out;
        LocalDateTime now = LocalDateTime.now(TZ);
        Instant at = Instant.now();
        List<Order> active = null;
        Set<Long> blocked = null;
        int slots = 0;
        Map<String, List<Order>> byRoute = new HashMap<>();
        for (Trip t : trips) {
            if (TripPlanner.departure(t, now) == null) continue;
            if (active == null) {
                active = orderRepository.findByDriverIdAndStatusIn(me.getId(), OrderRules.ACTIVE);
                blocked = blocks.related(me.getId());
                long parcels = active.stream().filter(o -> o.getKind() == OrderKind.PARCEL).count();
                slots = OrderRules.parcelSlots(rules.parcelCap(), parcels);
            }
            List<Order> open = byRoute.computeIfAbsent(t.getFromRegion().getId() + ">" + t.getToRegion().getId(),
                    k -> open(t, me, now));
            TripPlanner.Plan p = TripPlanner.plan(t, me, now, at, open, blocked, active, slots);
            if (p != null) out.put(t.getId(), PlanSummary.of(p));
        }
        return out;
    }

    /** Yangi reys uchun (e'lon qilingan zahoti ko'rsatiladi) */
    @Transactional(readOnly = true)
    public PlanSummary summary(User me, Trip t) {
        return summaries(me, List.of(t)).get(t.getId());
    }

    private List<Order> open(Trip t, User me, LocalDateTime now) {
        return orderRepository.findOpenOnRoute(t.getFromRegion().getId(), t.getToRegion().getId(), me.getId(),
                now.toLocalDate(), PageRequest.of(0, OPEN_LIMIT));
    }

    private static Long districtId(uz.yukgo.location.District d) {
        return d == null ? null : d.getId();
    }

    private static String districtName(uz.yukgo.location.District d) {
        return d == null ? null : d.getName();
    }
}
