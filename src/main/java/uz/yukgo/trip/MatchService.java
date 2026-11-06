package uz.yukgo.trip;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import uz.yukgo.notification.NotificationService;
import uz.yukgo.order.Order;
import uz.yukgo.order.OrderKind;
import uz.yukgo.order.OrderRepository;
import uz.yukgo.order.OrderRules;
import uz.yukgo.telegram.TelegramClient;
import uz.yukgo.user.BlockService;
import uz.yukgo.user.BodyType;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E'lon va reyslarni bir-biriga moslaydi va avtomatik xabar yuboradi:
 *  • yangi e'lon (yuk, pochta, yo'lovchi) → shu yo'nalishda reys e'lon qilgan haydovchilarga «Siz uchun yangi yo'lovchi»
 *    (ilovada ham, Telegramda ham), keyin uyi yoki ishlaydigan viloyati shu yerda bo'lgan haydovchilarga (Telegram) —
 *    «Faqat reyslarimga mos e'lonlar» yoqqan haydovchilardan tashqari (ularga faqat birinchi qadamdagi xabar boradi);
 *  • yangi reys → shu yo'nalishda haydovchi kutayotganlarga «Yo'nalishingizda haydovchi bor».
 * Reysi mos haydovchiga xabarda «Reysga yig'ish» maslahati ham bo'ladi: shu e'lon bilan reysiga yana nechta yo'lovchi
 * va pochta sig'adi ({@link TripPlanner}) — Telegramda «🧩 Reysni yig'ish» tugmasi bilan.
 * Blok bo'lgan juftliklar, haydovchi xabar olmaydigan e'lon turlari va mos kelmaydigan mashina turi hisobga olinadi.
 * Xatolar e'lon yoki reys yaratilishini to'xtatmaydi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchService {
    static final ZoneId TZ = ZoneId.of("Asia/Tashkent");
    /** Bitta e'lon haqida ko'pi bilan shuncha haydovchiga xabar */
    static final int MAX_DRIVERS = 300;
    /** Bitta reys haqida ko'pi bilan shuncha e'lon egasiga xabar */
    static final int MAX_SENDERS = 100;
    /** Bir haydovchining reyslari haqida bir odamga shu vaqt ichida bir martadan ortiq xabar bormaydi */
    static final Duration SENDER_QUIET = Duration.ofHours(12);
    /** Bitta e'lon haqida ko'pi bilan shuncha haydovchi uchun «Reysga yig'ish» hisoblanadi (har biriga 2 ta so'rov) */
    static final int MAX_PLANS = 50;

    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final BlockService blocks;
    private final NotificationService notifications;
    private final OrderRules rules;

    /** "haydovchiId:odamId" → oxirgi xabar vaqti (qayta-qayta reys ochib spam qilinmasin) */
    private final Map<String, Instant> senderNotified = new ConcurrentHashMap<>();

    // =====================================================================
    // Yangi e'lon → haydovchilar
    // =====================================================================

    /** Yangi, qayta ochilgan yoki shoshilinch qilingan e'lon. @return xabar yuborilgan haydovchilar soni */
    public int onNewOrder(Order o, User sender) {
        return onNewOrder(o, sender, null);
    }

    /** skipDriverId — xabar yuborilmaydigan haydovchi (masalan, shu e'londan hozirgina voz kechgan) */
    public int onNewOrder(Order o, User sender, Long skipDriverId) {
        try {
            return notifyDrivers(o, sender, LocalDateTime.now(TZ), skipDriverId);
        } catch (Exception e) {
            log.warn("Haydovchilarga xabar yuborishda xato: {}", TelegramClient.safe(e));
            return 0;
        }
    }

    int notifyDrivers(Order o, User sender, LocalDateTime now) {
        return notifyDrivers(o, sender, now, null);
    }

    int notifyDrivers(Order o, User sender, LocalDateTime now, Long skipDriverId) {
        Set<Long> blocked = blocks.related(sender.getId());   // yuboruvchi bilan blok bo'lganlarga xabar bormaydi
        Set<Long> done = new HashSet<>();
        done.add(sender.getId());
        if (skipDriverId != null) done.add(skipDriverId);
        int sent = 0;
        String link = "#/orders/" + o.getId();

        // 1) Reysi mos haydovchilar: «Siz uchun yangi yo'lovchi» — ilova ichida ham (Telegramsiz Android ilova uchun)
        LocalDateTime[] w = tripWindow(o, now);
        List<Trip> trips = tripRepository.findForOrder(o.getFromRegion().getId(), o.getToRegion().getId(), sender.getId(),
                w[0], w[1], PageRequest.of(0, 500));
        // Avval tumani aniq mos reyslar
        List<Trip> ordered = new ArrayList<>(trips);
        ordered.sort(Comparator.comparingInt(t -> sameFromDistrict(t, o) ? 0 : 1));
        List<Order> onRoute = null;   // «Reysga yig'ish» uchun: shu yo'nalishdagi ochiq e'lonlar — barcha haydovchilarga bitta so'rov
        Integer cap = null;
        int planned = 0;
        // Pochta: chegaraga yetgan (bir vaqtda N ta pochta olgan) haydovchilarga xabar bormaydi — baribir ola olmaydi
        boolean parcel = o.getKind() == OrderKind.PARCEL;
        Set<Long> full = parcel ? atCap(ordered.stream().map(t -> t.getDriver().getId()).toList()) : Set.of();
        for (Trip t : ordered) {
            User d = t.getDriver();
            if (done.contains(d.getId()) || blocked.contains(d.getId()) || full.contains(d.getId())) continue;
            if (!d.isNotifyNewOrders() || !kindAndVehicleFit(d, o) || !tripFits(t, o, now)) continue;
            done.add(d.getId());
            TripPlanner.Plan plan = null;
            if (planned++ < MAX_PLANS && (o.isPassenger() || o.getKind() == OrderKind.PARCEL)) {
                try {
                    if (onRoute == null) {
                        onRoute = orderRepository.findOpenOnRoute(o.getFromRegion().getId(), o.getToRegion().getId(), -1L,
                                now.toLocalDate(), PageRequest.of(0, 300));
                        cap = rules.parcelCap();
                    }
                    plan = planFor(t, d, now, onRoute, cap);
                } catch (Exception e) {
                    log.debug("Reysga yig'ish hisoblanmadi (reys {}): {}", t.getId(), e.toString());
                }
            }
            notifications.notify(d, TripNotes.tripMatch(o, t, plan), link);
            if (++sent >= MAX_DRIVERS) return sent;
        }

        // 2) Uyi yoki ishlaydigan viloyati e'lon yo'nalishida bo'lgan bo'sh haydovchilar — Telegram orqali (yoki push).
        //    «Faqat reyslarimga mos e'lonlar» yoqqanlar bu yerda xabar olmaydi (reysiga moslari — yuqorida)
        List<User> drivers = new ArrayList<>(userRepository.findDriversToNotify(
                o.getFromRegion().getId(), o.getToRegion().getId(), sender.getId()));
        drivers.sort(Comparator.comparingInt(d -> nearRank(d, o)));   // avval o'z tumanidan, keyin viloyatidan
        Set<Long> full2 = parcel ? atCap(drivers.stream().map(User::getId).toList()) : Set.of();
        for (User d : drivers) {
            if (done.contains(d.getId()) || blocked.contains(d.getId()) || d.isRouteOnly() || full2.contains(d.getId())) continue;
            if (!kindAndVehicleFit(d, o)) continue;
            done.add(d.getId());
            boolean near = nearRank(d, o) == 0;
            // Telegrami ulanmagan (Android ilovadagi) haydovchiga — push
            if (!notifications.telegramOrPush(d, TripNotes.newOrder(o, near), link)) continue;
            if (++sent >= MAX_DRIVERS) break;
        }
        return sent;
    }

    /** Pochta chegarasiga yetganlar; hisoblab bo'lmasa — hech kim (xabarlar avvalgidek ketadi) */
    private Set<Long> atCap(List<Long> driverIds) {
        try {
            return rules.atParcelCap(driverIds);
        } catch (Exception e) {
            log.debug("Pochta chegarasi tekshirilmadi: {}", e.toString());
            return Set.of();
        }
    }

    /** Haydovchining shu reysi uchun «Reysga yig'ish» (mashinadagilar, pochta chegarasi va blok hisobga olinadi) */
    TripPlanner.Plan planFor(Trip t, User d, LocalDateTime now, List<Order> onRoute, int cap) {
        List<Order> active = orderRepository.findByDriverIdAndStatusIn(d.getId(), OrderRules.ACTIVE);
        long parcels = active.stream().filter(x -> x.getKind() == OrderKind.PARCEL).count();
        return TripPlanner.plan(t, d, now, Instant.now(), onRoute, blocks.related(d.getId()), active,
                OrderRules.parcelSlots(cap, parcels));
    }

    /** E'lon sanasiga mos reyslar oralig'i: o'sha kuni yoki ertasiga; sanasiz e'longa — keyingi 3 kun */
    static LocalDateTime[] tripWindow(Order o, LocalDateTime now) {
        LocalDateTime earliest = now.minusHours(3);   // jo'nab ketganiga ko'p bo'lmagan reys ham
        if (o.getPickupDate() == null) return new LocalDateTime[]{earliest, now.plusDays(3)};
        LocalDateTime start = o.getPickupDate().atStartOfDay();
        return new LocalDateTime[]{start.isBefore(earliest) ? earliest : start, o.getPickupDate().plusDays(2).atStartOfDay()};
    }

    /** Reys sanasi, joylar soni e'longa mosmi (yo'nalish so'rovning o'zida tekshirilgan) */
    public static boolean tripFits(Trip t, Order o, LocalDateTime now) {
        if (!t.isActive()) return false;
        if (o.isPassenger() && t.getSeats() != null && o.getSeats() != null && o.getSeats() > t.getSeats()) return false;
        if (t.isDaily()) return true;
        LocalDateTime[] w = tripWindow(o, now);
        return !t.getDepartAt().isBefore(w[0]) && t.getDepartAt().isBefore(w[1]);
    }

    /** Haydovchi bu turdagi e'lonlarni oladimi va mashinasi mosmi (yuk egasi aniq turini so'ragan bo'lsa) */
    public static boolean kindAndVehicleFit(User d, Order o) {
        if (!d.isActive() || !d.isDriver()) return false;
        if (!d.acceptsKind(o.getKind())) return false;
        BodyType need = o.getRequiredBodyType();
        BodyType has = d.getVehicle().getBodyType();
        return need == null || has == null || BodyType.same(need, has);
    }

    /** Reys va e'lon bir tumandan (yoki biri butun viloyat) */
    static boolean sameFromDistrict(Trip t, Order o) {
        return t.getFromDistrict() == null || o.getFromDistrict() == null
                || t.getFromDistrict().getId().equals(o.getFromDistrict().getId());
    }

    /** 0 — haydovchining tumanidan, 1 — viloyatidan, 2 — boshqa (masalan, viloyatiga keladigan e'lon) */
    static int nearRank(User d, Order o) {
        if (d.getDistrict() != null && o.getFromDistrict() != null && d.getDistrict().getId().equals(o.getFromDistrict().getId())) return 0;
        if (d.getRegion() != null && d.getRegion().getId().equals(o.getFromRegion().getId())) return 1;
        return 2;
    }

    static String tripMatchTitle(Order o) {
        return switch (o.getKind()) {
            case PASSENGER -> "🎯 Siz uchun yangi yo'lovchi";
            case PARCEL -> "🎯 Siz uchun yangi pochta";
            default -> "🎯 Siz uchun yangi buyurtma";
        };
    }

    // =====================================================================
    // Yangi reys → e'lon egalari
    // =====================================================================

    /** @return shu reysga mos ochiq e'lonlar soni (egalariga xabar ketdi) */
    public int onNewTrip(Trip t) {
        try {
            return notifySenders(t, LocalDateTime.now(TZ), Instant.now());
        } catch (Exception e) {
            log.warn("Reysga mos e'lon egalariga xabar yuborishda xato: {}", TelegramClient.safe(e));
            return 0;
        }
    }

    int notifySenders(Trip t, LocalDateTime now, Instant at) {
        User d = t.getDriver();
        if (d == null || !d.isActive() || !d.isDriver()) return 0;
        Set<Long> blocked = blocks.related(d.getId());
        List<Order> open = orderRepository.findOpenOnRoute(t.getFromRegion().getId(), t.getToRegion().getId(), d.getId(),
                now.toLocalDate(), PageRequest.of(0, 300));
        pruneQuiet(at);
        int matched = 0, sent = 0;
        Set<Long> told = new HashSet<>();
        for (Order o : open) {
            User s = o.getSender();
            if (blocked.contains(s.getId())) continue;
            if (!kindAndVehicleFit(d, o) || !tripFits(t, o, now)) continue;
            matched++;
            if (sent >= MAX_SENDERS || !told.add(s.getId())) continue;
            String key = d.getId() + ":" + s.getId();
            Instant last = senderNotified.get(key);
            if (last != null && last.isAfter(at.minus(SENDER_QUIET))) continue;
            senderNotified.put(key, at);
            notifications.notify(s, TripNotes.driverOnRoute(d, t), "#/orders/" + o.getId());
            sent++;
        }
        return matched;
    }

    private void pruneQuiet(Instant at) {
        if (senderNotified.size() < 5000) return;
        Instant limit = at.minus(SENDER_QUIET);
        senderNotified.values().removeIf(i -> i.isBefore(limit));
    }
}
