package uz.yukgo.trip;

import uz.yukgo.order.Order;
import uz.yukgo.order.OrderKind;
import uz.yukgo.user.BodyType;
import uz.yukgo.user.User;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * «Reysga yig'ish»: haydovchining reysiga mos e'lonlardan bitta reysda olib ketiladigan to'plam —
 * bo'sh o'rinlarni to'ldiradigan yo'lovchilar va 2 tagacha pochta (haydovchining pochta chegarasigacha).
 * <p>
 * Mos e'lon: shu viloyatdan shu viloyatga, reys kuni uchun (yoki sanasiz, yaqinda berilgan), haydovchi oladigan tur va
 * mashinaga mos, blok yo'q. Avval reys tumanidan va boradigan tumanidan, keyin birinchi berilgani (navbat bilan).
 * Yo'lovchilar o'rinlarni eng ko'p to'ldiradigan qilib tanlanadi (masalan, 4 o'ringa 3 + 2 emas, 2 + 2).
 * Ma'lumotlar bazasiga murojaat yo'q — hammasi chaqiruvchidan keladi (sinash oson).
 */
public final class TripPlanner {
    private TripPlanner() {}

    /** Tavsiyada ko'pi bilan shuncha pochta (haydovchining chegarasi kamroq qoldirsa — o'shancha) */
    public static final int PLAN_PARCELS = 2;
    /** O'rinlar soni ko'rsatilmagan reys: yengil mashina va boshqalar — 4, furgon — 6, yuk mashinasi kabinasi — 2 */
    static final int DEFAULT_SEATS = 4;
    static final int FURGON_SEATS = 6;
    static final int CAB_SEATS = 2;
    /** Sanasiz e'lonlar — shuncha vaqt ichida berilganlari */
    static final Duration NO_DATE_FRESH = Duration.ofDays(3);
    /** Jo'nash vaqtidan shuncha o'tguncha reys hali «bugungi» (yo'lda olib ketishi mumkin) */
    static final Duration LATE_GRACE = Duration.ofHours(2);
    /** O'rinlarni to'ldirishda ko'pi bilan shuncha nomzod ko'riladi (eng ustuvorlari) */
    static final int MAX_CANDIDATES = 40;

    /**
     * @param departAt       qaysi jo'nash uchun (har kuni reysda — bugungi yoki ertangi)
     * @param capacity       o'rinlar: reysda yozilgan bo'sh o'rinlar + undan oldin olingan yo'lovchilar (yoki mashina turiga qarab taxminiy)
     * @param seatsInCar     shu kunga allaqachon olgan yo'lovchilari (kishi)
     * @param inCar          shu reys kuniga olgan buyurtmalari (yo'lovchi va pochta)
     * @param passengers     tavsiya: o'rinlarni to'ldiradigan yo'lovchilar
     * @param parcels        tavsiya: pochtalar (ko'pi bilan {@link #PLAN_PARCELS})
     * @param parcelSlots    pochta chegarasigacha yana nechta olishi mumkin (Integer.MAX_VALUE — cheklovsiz)
     * @param morePassengers sig'magan yoki tanlanmagan mos yo'lovchi e'lonlari
     * @param moreParcels    tavsiyaga kirmagan mos pochtalar (boshqa haydovchilarga qoladi)
     */
    public record Plan(Trip trip, LocalDateTime departAt, int capacity, boolean capacityGuessed, int seatsInCar,
                       List<Order> inCar, List<Order> passengers, int seatsFilled, List<Order> parcels, int parcelSlots,
                       int morePassengers, int moreParcels) {
        public LocalDate day() {
            return departAt.toLocalDate();
        }

        public int freeSeats() {
            return Math.max(0, capacity - seatsInCar - seatsFilled);
        }

        public int items() {
            return passengers.size() + parcels.size();
        }

        public boolean isEmpty() {
            return items() == 0;
        }
    }

    /**
     * Reysning eng yaqin jo'nashi: oddiy reys — o'z vaqti (jo'nab ketganiga 2 soatdan oshgan bo'lsa — null);
     * «har kuni» — bugungi (2 soat o'tmagan bo'lsa), aks holda ertangi. Yopilgan reys — null.
     */
    public static LocalDateTime departure(Trip t, LocalDateTime now) {
        if (t == null || !t.isActive() || t.getDepartAt() == null) return null;
        LocalDateTime limit = now.minus(LATE_GRACE);
        if (!t.isDaily()) return t.getDepartAt().isBefore(limit) ? null : t.getDepartAt();
        LocalDateTime today = now.toLocalDate().atTime(t.getDepartAt().toLocalTime());
        return today.isBefore(limit) ? today.plusDays(1) : today;
    }

    /** Reysda o'rinlar ko'rsatilmagan bo'lsa — mashina turiga qarab */
    static int defaultSeats(User driver) {
        BodyType b = driver == null || driver.getVehicle() == null ? null : BodyType.canonical(driver.getVehicle().getBodyType());
        if (b == null) return DEFAULT_SEATS;
        return switch (b) {
            case FURGON -> FURGON_SEATS;
            case TRUCK, BORT, CONTAINER -> CAB_SEATS;
            default -> DEFAULT_SEATS;
        };
    }

    /**
     * @param open         shu yo'nalishdagi ochiq e'lonlar (boshqa yo'nalishlari ham bo'lishi mumkin — tashlab yuboriladi)
     * @param blocked      haydovchi bilan blok bo'lgan foydalanuvchilar
     * @param driverActive haydovchining olgan, hali yetkazmagan buyurtmalari (mashinadagi o'rinlar uchun)
     * @param parcelSlots  pochta chegarasigacha yana nechta (Integer.MAX_VALUE — cheklovsiz)
     * @return null — reys o'tib ketgan yoki yopilgan
     */
    public static Plan plan(Trip t, User driver, LocalDateTime now, Instant nowInstant, Collection<Order> open,
                            Set<Long> blocked, Collection<Order> driverActive, int parcelSlots) {
        LocalDateTime when = departure(t, now);
        if (when == null || driver == null) return null;
        LocalDate day = when.toLocalDate();
        Instant fresh = nowInstant.minus(NO_DATE_FRESH);

        // Shu reys kuniga allaqachon olganlari (shu yo'nalishda). Reysda «bo'sh o'rinlar» yozilgan bo'lsa, undan OLDIN
        // olingan yo'lovchilar o'sha songa kirmagan (haydovchi bo'sh qolganini yozgan) — o'rinlar soniga qo'shiladi;
        // KEYIN olinganlari esa shu bo'sh o'rinlarni egallaydi
        Instant declared = t.getSeats() == null ? null : t.getSeatsAt() != null ? t.getSeatsAt() : t.getCreatedAt();
        List<Order> inCar = new ArrayList<>();
        int seatsInCar = 0, takenBefore = 0;
        for (Order o : driverActive == null ? List.<Order>of() : driverActive) {
            if (!sameRoute(t, o) || (o.getPickupDate() != null && !o.getPickupDate().equals(day))) continue;
            inCar.add(o);
            if (!o.isPassenger()) continue;
            seatsInCar += seats(o);
            if (declared != null && o.getAcceptedAt() != null && !o.getAcceptedAt().isAfter(declared)) takenBefore += seats(o);
        }

        List<Order> passengers = new ArrayList<>(), parcels = new ArrayList<>();
        for (Order o : open) {
            if (!fits(t, driver, o, day, fresh, blocked)) continue;
            if (o.isPassenger()) passengers.add(o);
            else if (o.getKind() == OrderKind.PARCEL) parcels.add(o);
        }
        Comparator<Order> rank = rank(t);
        passengers.sort(rank);
        parcels.sort(rank);

        // Mashinadagi hamma o'rinlar: yozilgan bo'shlari + undan oldin olinganlar; yozilmagan — mashina turiga qarab (taxminiy)
        int capacity = t.getSeats() != null ? t.getSeats() + takenBefore : defaultSeats(driver);
        int room = Math.max(0, capacity - seatsInCar);
        List<Order> chosen = fill(passengers, room);
        int filled = 0;
        for (Order o : chosen) filled += seats(o);

        int parcelCount = Math.max(0, Math.min(PLAN_PARCELS, Math.min(parcelSlots, parcels.size())));
        List<Order> chosenParcels = new ArrayList<>(parcels.subList(0, parcelCount));
        return new Plan(t, when, capacity, t.getSeats() == null, seatsInCar, inCar, chosen, filled, chosenParcels,
                parcelSlots, passengers.size() - chosen.size(), parcels.size() - chosenParcels.size());
    }

    /** E'lon shu reys kuniga va haydovchiga mosmi (yo'nalish, sana, tur, mashina, blok) */
    static boolean fits(Trip t, User driver, Order o, LocalDate day, Instant fresh, Set<Long> blocked) {
        if (o.getDriver() != null || o.getStatus() != uz.yukgo.order.OrderStatus.NEW) return false;
        if (!sameRoute(t, o)) return false;
        if (o.getSender() == null || o.getSender().getId().equals(driver.getId())) return false;
        if (blocked != null && blocked.contains(o.getSender().getId())) return false;
        if (o.getKind() != OrderKind.PASSENGER && o.getKind() != OrderKind.PARCEL) return false;
        if (!MatchService.kindAndVehicleFit(driver, o)) return false;
        if (o.getPickupDate() != null) return o.getPickupDate().equals(day);
        return o.getCreatedAt() != null && o.getCreatedAt().isAfter(fresh);
    }

    static boolean sameRoute(Trip t, Order o) {
        return o.getFromRegion() != null && o.getToRegion() != null
                && o.getFromRegion().getId().equals(t.getFromRegion().getId())
                && o.getToRegion().getId().equals(t.getToRegion().getId());
    }

    /** Avval reys tumanidan, keyin boradigan tumanidan, sanasi aniq (shu kunga) bo'lganlari, so'ng birinchi berilgani */
    static Comparator<Order> rank(Trip t) {
        return Comparator.<Order>comparingInt(o -> MatchService.sameFromDistrict(t, o) ? 0 : 1)
                .thenComparingInt(o -> sameToDistrict(t, o) ? 0 : 1)
                .thenComparingInt(o -> o.getPickupDate() != null ? 0 : 1)
                .thenComparing(o -> o.getCreatedAt() == null ? Instant.EPOCH : o.getCreatedAt())
                .thenComparing(o -> o.getId() == null ? Long.MAX_VALUE : o.getId());
    }

    static boolean sameToDistrict(Trip t, Order o) {
        return t.getToDistrict() == null || o.getToDistrict() == null
                || t.getToDistrict().getId().equals(o.getToDistrict().getId());
    }

    static int seats(Order o) {
        return o.getSeats() == null || o.getSeats() < 1 ? 1 : o.getSeats();
    }

    /**
     * O'rinlarni eng ko'p to'ldiradigan yo'lovchilar (0/1 ryukzak): teng to'ldirilsa — ustuvorroqlari (ro'yxat boshidagilar)
     * va kamroq e'lon. Natija ustuvorlik tartibida.
     */
    static List<Order> fill(List<Order> ranked, int room) {
        if (room <= 0 || ranked.isEmpty()) return new ArrayList<>();
        List<Order> items = new ArrayList<>();
        for (Order o : ranked) {
            if (seats(o) <= room) items.add(o);
            if (items.size() == MAX_CANDIDATES) break;
        }
        int n = items.size();
        long[][] best = new long[n + 1][room + 1];
        for (int i = 1; i <= n; i++) {
            int w = seats(items.get(i - 1));
            long v = w * 1000L - (i - 1);   // o'rin — asosiy, ustuvorlik — teng bo'lganda
            for (int c = 0; c <= room; c++) {
                best[i][c] = best[i - 1][c];
                if (w <= c && best[i - 1][c - w] + v > best[i][c]) best[i][c] = best[i - 1][c - w] + v;
            }
        }
        List<Order> out = new ArrayList<>();
        int c = room;
        for (int i = n; i >= 1; i--) {
            if (best[i][c] != best[i - 1][c]) {
                out.add(items.get(i - 1));
                c -= seats(items.get(i - 1));
            }
        }
        Collections.reverse(out);
        return out;
    }
}
