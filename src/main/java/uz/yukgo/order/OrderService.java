package uz.yukgo.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uz.yukgo.common.ApiException;
import uz.yukgo.common.PageResponse;
import uz.yukgo.common.Phones;
import uz.yukgo.channel.ChannelHooks;
import uz.yukgo.config.AppProperties;
import uz.yukgo.growth.GrowthService;
import uz.yukgo.file.FileStorageService;
import uz.yukgo.location.District;
import uz.yukgo.location.LocationService;
import uz.yukgo.location.Region;
import uz.yukgo.notification.NotificationService;
import uz.yukgo.order.OrderDtos.*;
import uz.yukgo.review.ReviewRepository;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.telegram.TelegramClient;
import uz.yukgo.telegram.TelegramLinkRepository;
import uz.yukgo.user.BlockService;
import uz.yukgo.user.BodyType;
import uz.yukgo.user.Terms;
import uz.yukgo.user.User;
import uz.yukgo.user.UserDtos.UserBrief;
import uz.yukgo.user.UserRepository;
import uz.yukgo.wallet.TxType;
import uz.yukgo.wallet.WalletService;

import java.security.SecureRandom;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<OrderStatus> ALL = List.of(OrderStatus.values());
    /** "Bugun" doim Toshkent vaqti bo'yicha (server UTC'da ishlaydi). */
    private static final ZoneId TZ = ZoneId.of("Asia/Tashkent");

    private final OrderRepository orderRepository;
    private final OfferRepository offerRepository;
    private final OrderEventRepository eventRepository;
    private final TrackPointRepository trackRepository;
    private final OrderMessageRepository messageRepository;
    private final MessageReadRepository readRepository;
    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;
    private final TelegramLinkRepository linkRepository;
    private final LocationService locationService;
    private final NotificationService notifications;
    private final TelegramClient telegram;
    private final FileStorageService files;
    private final CurrentUser currentUser;
    private final AppProperties props;
    private final WalletService wallet;
    private final GrowthService growth;
    private final ChannelHooks channels;
    private final uz.yukgo.common.RowLocks locks;
    private final BlockService blocks;
    private final uz.yukgo.trip.MatchService matches;
    private final OrderRules rules;

    // =====================================================================
    // Yaratish va ko'rish
    // =====================================================================

    @Transactional
    public OrderDto create(CreateOrderRequest req) {
        User me = currentUser.get();
        return toDto(createOrder(me, req).order(), me, true);
    }

    /** Yaratilgan e'lon va xabar yuborilgan haydovchilar soni (duplicate=true — xuddi shunday e'lon yaqinda joylangan, yangisi ochilmadi) */
    public record Created(Order order, int notified, boolean duplicate) {}

    /** Botga yozilgan e'lon: kuniga ko'pi bilan shuncha (spam va xato yuborishdan himoya; saytdagi forma cheklanmaydi) */
    static final int BOT_DAILY_LIMIT = 20;
    /** Shu vaqt ichida xuddi shunday e'lon qayta yuborilsa — yangisi ochilmaydi (odamlar xabarni ikki marta yuboradi) */
    static final Duration BOT_DUPLICATE_WINDOW = Duration.ofMinutes(30);

    /**
     * Telegram bot: ulangan foydalanuvchi botga yozgan e'lon — forma to'ldirilmasdan darhol saytga chiqadi.
     * Xuddi shunday (yo'nalish, tur, sana) e'lon yaqinda joylangan bo'lsa — o'sha qaytariladi.
     * Xatolar (ApiException) hech narsa yozilmasdan oldin chiqadi va tranzaksiyani buzmaydi — bot ularni matn qilib aytadi.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Created createFromBot(User me, CreateOrderRequest req) {
        Instant now = Instant.now();
        OrderKind kind = req.kind() == null ? OrderKind.CARGO : req.kind();
        for (Order o : orderRepository.findBySenderIdAndStatusAndCreatedAtAfterOrderByIdDesc(me.getId(), OrderStatus.NEW,
                now.minus(BOT_DUPLICATE_WINDOW))) {
            // Xuddi o'sha xabar (bir xil matn, yo'nalish, tur va sana) — boshqa yuk bo'lsa yangi e'lon ochiladi
            if (o.getKind() == kind && o.getFromRegion().getId().equals(req.from().regionId())
                    && o.getToRegion().getId().equals(req.to().regionId())
                    && Objects.equals(o.getFromDistrict() == null ? null : o.getFromDistrict().getId(), req.from().districtId())
                    && Objects.equals(o.getToDistrict() == null ? null : o.getToDistrict().getId(), req.to().districtId())
                    && (req.pickupDate() == null || Objects.equals(o.getPickupDate(), req.pickupDate()))
                    && Objects.equals(o.getDescription(), trimToNull(req.description()))
                    && (kind == OrderKind.PASSENGER ? Objects.equals(o.getSeats(), req.seats() == null ? 1 : req.seats())
                            : Objects.equals(o.getTitle(), trimToNull(req.title())))) {
                return new Created(o, 0, true);
            }
        }
        if (orderRepository.countBySenderIdAndCreatedAtAfter(me.getId(), now.minus(Duration.ofDays(1))) >= BOT_DAILY_LIMIT) {
            throw ApiException.badRequest("Bugun juda ko'p e'lon berdingiz. Eskilarini saytda ko'ring yoki ertaga qayta urinib ko'ring");
        }
        return createOrder(me, req);
    }

    Created createOrder(User me, CreateOrderRequest req) {
        Terms.require(me);
        OrderKind kind = req.kind() == null ? OrderKind.CARGO : req.kind();
        Region fromRegion = locationService.region(req.from().regionId());
        Region toRegion = locationService.region(req.to().regionId());
        District fromDistrict = req.from().districtId() == null ? null : locationService.district(req.from().districtId(), fromRegion);
        District toDistrict = req.to().districtId() == null ? null : locationService.district(req.to().districtId(), toRegion);
        LocalDate today = LocalDate.now(TZ);
        if (req.pickupDate() != null && req.pickupDate().isBefore(today)) {
            throw ApiException.badRequest((kind == OrderKind.PASSENGER ? "Jo'nash" : "Olib ketish")
                    + " sanasi o'tib ketgan — bugungi yoki keyingi sanani tanlang");
        }
        if (req.pickupDate() != null && req.pickupDate().isAfter(today.plusDays(90))) {
            throw ApiException.badRequest("Sana juda uzoq — 3 oy ichidagi sanani tanlang");
        }
        String title = trimToNull(req.title());
        if (kind == OrderKind.CARGO && title == null) throw ApiException.badRequest("Nima yuborayotganingizni yozing");
        int seats = req.seats() == null ? 1 : req.seats();

        Order o = new Order();
        o.setSender(me);
        o.setKind(kind);
        switch (kind) {
            case PASSENGER -> {
                // Yo'lovchidan faqat yo'nalish, sana va necha kishi so'raladi
                o.setSeats(seats);
                o.setTitle(seats + " yo'lovchi");
                o.setCargoType(CargoType.GENERAL);
            }
            case PARCEL -> {
                o.setTitle(title == null ? "Pochta" : title);
                o.setCargoType(CargoType.PARCEL);
                o.setWeightKg(req.weightKg());
            }
            default -> {
                o.setTitle(title);
                o.setCargoType(req.cargoType() == null ? CargoType.GENERAL : req.cargoType());
                o.setWeightKg(req.weightKg());
                o.setVolumeM3(req.volumeM3());
                o.setRequiredBodyType(BodyType.canonical(req.requiredBodyType()));
            }
        }
        o.setDescription(trimToNull(req.description()));
        o.setFromRegion(fromRegion);
        o.setFromDistrict(fromDistrict);
        o.setFromAddress(trimToNull(req.from().address()));
        o.setFromLat(req.from().lat());
        o.setFromLng(req.from().lng());
        o.setToRegion(toRegion);
        o.setToDistrict(toDistrict);
        o.setToAddress(trimToNull(req.to().address()));
        o.setToLat(req.to().lat());
        o.setToLng(req.to().lng());
        o.setDistanceKm(req.distanceKm());
        o.setDurationMin(req.durationMin());
        o.setScope(scopeOf(fromRegion, fromDistrict, toRegion, toDistrict));
        // Yo'lovchi sanasiz bo'lmaydi: ko'rsatilmasa — bugun
        o.setPickupDate(req.pickupDate() == null && kind == OrderKind.PASSENGER ? today : req.pickupDate());
        o.setPrice(req.price());
        o.setPaymentMethod(req.paymentMethod() == null ? PaymentMethod.CASH : req.paymentMethod());
        o.setPayer(req.payer() == null ? Payer.SENDER : req.payer());
        o.setUrgent(req.urgent());
        if (kind != OrderKind.PASSENGER) {
            // Qabul qiluvchi ko'rsatilmasa — yuboruvchining o'zi (topshirish kodini o'zi aytadi)
            String phone = trimToNull(req.receiverPhone());
            if (phone == null) {
                o.setReceiverName(me.getFullName());
                o.setReceiverPhone(me.getPhone());
            } else {
                o.setReceiverPhone(Phones.normalize(phone));
                String name = trimToNull(req.receiverName());
                o.setReceiverName(name != null ? name : o.getReceiverPhone().equals(me.getPhone()) ? me.getFullName() : null);
            }
        }
        o.setDeliveryCode(String.format("%04d", RANDOM.nextInt(10_000)));
        orderRepository.save(o);
        if (o.isUrgent()) chargeUrgentFee(o, me);

        me.setOrdersCreated(me.getOrdersCreated() + 1);
        event(o, OrderStatus.NEW, kind == OrderKind.PASSENGER ? "Yo'lovchi e'loni joylandi" : "Buyurtma e'lon qilindi", me);
        int notified = matches.onNewOrder(o, me);   // reysi mos va yaqindagi haydovchilarga xabar
        channels.orderChanged(o.getId());   // YukGo va hamkor Telegram kanallariga e'lon (saqlangandan keyin)
        return new Created(o, notified, false);
    }

    @Transactional
    public OrderDto uploadPhoto(Long orderId, MultipartFile file) {
        User me = currentUser.get();
        Terms.require(me);
        Order o = find(orderId);
        requireSender(o, me);
        if (o.getStatus() != OrderStatus.NEW) throw ApiException.badRequest("Rasmni faqat yangi buyurtmaga qo'shish mumkin");
        String url = files.storeImage(file, "orders");
        files.delete(o.getPhotoUrl());
        o.setPhotoUrl(url);
        return toDto(o, me, true);
    }

    /**
     * Ochiq e'lonlar. Kirgan foydalanuvchiga avval o'z tumanidan, keyin o'z viloyatidan, so'ng yaqin viloyatlardan
     * chiqqan e'lonlar (ro'yxatdan o'tishda ko'rsatgan manzili bo'yicha); har birining ichida shoshilinchlar tepada.
     * Tuman tanlansa — shu tumandagi va tumani ko'rsatilmagan (butun viloyat) e'lonlar.
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderDto> market(MarketFilter f, int page) {
        User viewer = currentUserOrNull();
        String like = f.q() == null || f.q().isBlank() ? null : "%" + f.q().trim().toLowerCase() + "%";
        Region home = viewer == null ? null : viewer.getRegion();
        District homeDistrict = viewer == null ? null : viewer.getDistrict();
        var result = orderRepository.market(f.kind(), f.fromRegion(), f.fromDistrict(), f.toRegion(), f.toDistrict(),
                f.cargoType(), f.scope(), f.bodyType() == null,
                f.bodyType() == null ? List.of(BodyType.OTHER) : f.bodyType().withLegacy(),
                f.maxWeight(), like, LocalDate.now(TZ), viewer == null ? null : viewer.getId(),
                home == null ? null : home.getId(), homeDistrict == null ? null : homeDistrict.getId(),
                home == null ? null : home.getLat(), home == null ? null : home.getLng(),
                PageRequest.of(Math.max(0, page), 20));
        return PageResponse.of(result, o -> toDto(o, viewer));
    }

    /** Bozor filtrlari (hammasi ixtiyoriy). cargoType, scope, bodyType, maxWeight, q — eski ilova versiyalari uchun */
    public record MarketFilter(OrderKind kind, Long fromRegion, Long fromDistrict, Long toRegion, Long toDistrict,
                               CargoType cargoType, DeliveryScope scope, BodyType bodyType, Double maxWeight, String q) {}

    @Transactional
    public OrderDto get(Long id) {
        return get(id, true);
    }

    /**
     * Mehmon (ulashilgan havola) faqat ochiq e'lonni ko'radi — raqam, aniq manzil va kodsiz; yopilgan, olingan yoki
     * yetkazilgan buyurtmalar (haydovchi va mashinasi bilan) unga ko'rinmaydi.
     * @param countView sahifa birinchi ochilganda (o'zini yangilaganda emas) — kirgan begona foydalanuvchi ko'rishi sanaladi
     */
    @Transactional
    public OrderDto get(Long id, boolean countView) {
        User viewer = currentUserOrNull();
        Order o = find(id);
        if (viewer == null && (o.getStatus() != OrderStatus.NEW || o.getSender().getStatus() != uz.yukgo.user.UserStatus.ACTIVE)) {
            throw ApiException.notFound("E'lon topilmadi yoki yopilgan");
        }
        if (countView && viewer != null && !isParticipant(o, viewer)) o.setViewsCount(o.getViewsCount() + 1);
        return toDto(o, viewer, true);
    }

    /**
     * @param role sender | driver | offers
     * @param group active | done | all
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderDto> my(String role, String group, int page) {
        User me = currentUser.get();
        var pageable = PageRequest.of(page, 20);
        if ("offers".equals(role)) {
            return PageResponse.of(orderRepository.findWithMyPendingOffer(me.getId(), pageable), o -> toDto(o, me));
        }
        List<OrderStatus> statuses = switch (group == null ? "all" : group) {
            case "active" -> List.of(OrderStatus.NEW, OrderStatus.ACCEPTED, OrderStatus.IN_TRANSIT);
            case "done" -> List.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED);
            default -> ALL;
        };
        // «Hammasi»: bergan, olgan va narx taklif qilgan buyurtmalarim birga (driver, offers — eski ilova versiyalari uchun)
        var result = "all".equals(role) ? orderRepository.findMine(me.getId(), statuses, pageable)
                : "driver".equals(role) ? orderRepository.findByDriver(me.getId(), statuses, pageable)
                : orderRepository.findBySender(me.getId(), statuses, pageable);
        return PageResponse.of(result, o -> toDto(o, me));
    }

    // =====================================================================
    // Takliflar
    // =====================================================================

    @Transactional
    public OfferDto makeOffer(Long orderId, OfferRequest req) {
        User me = currentUser.get();
        Terms.require(me);
        // Qator bloklanadi: bir vaqtda bloklash (BlockService ochiq takliflarni shu qator bloklangan holda yopadi) yoki
        // qabul qilish bilan to'qnashmasin — taklif yopilgandan keyin eski holat bilan qayta «PENDING» bo'lib qolmasin
        Order o = findForUpdate(orderId);
        requireDriverProfile(me);
        if (o.getSender().getId().equals(me.getId())) throw ApiException.badRequest("O'z buyurtmangizga taklif bera olmaysiz");
        if (o.getStatus() != OrderStatus.NEW) throw ApiException.badRequest("Bu buyurtma endi taklif qabul qilmaydi");
        blocks.requireNone(me.getId(), o.getSender().getId(), "Bu buyurtmaga taklif bera olmaysiz");
        requireParcelSlot(me, o);
        // Daromad tizimi yoqilgan bo'lsa: qabul qilinganda komissiyani to'lay olishini oldindan tekshiramiz
        // (yangi haydovchining bonusli — komissiyasiz — buyurtmalari bo'lsa, balans shart emas)
        if (wallet.freeDeliveriesLeft(me) <= 0) wallet.requireBalanceForCommission(me, req.price());

        Offer offer = offerRepository.findFirstByOrderIdAndDriverIdAndStatus(orderId, me.getId(), OfferStatus.PENDING)
                .orElse(null);
        boolean isNew = offer == null;
        if (isNew) {
            offer = new Offer();
            offer.setOrder(o);
            offer.setDriver(me);
        }
        offer.setPrice(req.price());
        offer.setComment(trimToNull(req.comment()));
        offer.setPickupInHours(req.pickupInHours());
        offer.setUpdatedAt(Instant.now());
        offerRepository.save(offer);
        o.setOffersCount((int) offerRepository.countByOrderIdAndStatus(orderId, OfferStatus.PENDING));

        notifications.notify(o.getSender(), OrderNotes.newOffer(o, me, req.price(), isNew), "#/orders/" + o.getId());
        return OfferDto.of(offer);
    }

    @Transactional(readOnly = true)
    public List<OfferDto> offers(Long orderId) {
        User me = currentUser.get();
        Order o = find(orderId);
        boolean sender = o.getSender().getId().equals(me.getId());
        // Yuboruvchiga blok bo'lgan haydovchilarning takliflari ko'rsatilmaydi (bloklashda ochiqlari yopiladi)
        Set<Long> hidden = sender ? blocks.related(me.getId()) : Set.of();
        return offerRepository.findByOrderIdOrderByPriceAscCreatedAtAsc(orderId).stream()
                .filter(f -> sender || me.getRole().isStaff() || f.getDriver().getId().equals(me.getId()))
                .filter(f -> f.getStatus() != OfferStatus.WITHDRAWN || !sender)
                .filter(f -> !hidden.contains(f.getDriver().getId()))
                .map(OfferDto::of)
                .toList();
    }

    @Transactional
    public OrderDto acceptOffer(Long orderId, Long offerId) {
        User me = currentUser.get();
        Order o = findForUpdate(orderId);
        requireSender(o, me);
        if (o.getStatus() != OrderStatus.NEW) throw ApiException.badRequest("Buyurtma allaqachon band qilingan");
        Offer offer = offerRepository.findById(offerId)
                .filter(f -> f.getOrder().getId().equals(orderId))
                .orElseThrow(() -> ApiException.notFound("Taklif topilmadi"));
        if (offer.getStatus() != OfferStatus.PENDING) throw ApiException.badRequest("Bu taklif endi faol emas");
        if (!offer.getDriver().isActive()) throw ApiException.badRequest("Bu haydovchi hisobi faol emas");
        blocks.requireNone(me.getId(), offer.getDriver().getId(), "Bu taklifni qabul qilib bo'lmaydi");
        assignDriver(o, offer, me);
        return toDto(o, me, true);
    }

    /**
     * Haydovchi e'lonni darhol oladi: narx ko'rsatilgan bo'lsa — shu narxga rozi bo'lib. Pochta va yo'lovchi e'lonida
     * narx ko'rsatilmagan bo'lsa ham olish mumkin — ikki tomonga bir-birining telefoni ochiladi va narxni o'zlari
     * kelishadi (yukda esa narxsiz e'longa taklif yuboriladi).
     */
    @Transactional
    public OrderDto take(Long orderId) {
        User me = currentUser.get();
        Terms.require(me);
        Order o = findForUpdate(orderId);
        requireDriverProfile(me);
        if (o.getSender().getId().equals(me.getId())) throw ApiException.badRequest("O'z e'loningizni ola olmaysiz");
        if (o.getStatus() != OrderStatus.NEW) throw ApiException.badRequest("Bu e'lonni allaqachon boshqa haydovchi oldi");
        if (o.getPrice() == null && o.getKind() == OrderKind.CARGO) {
            throw ApiException.badRequest("Bu buyurtmada narx kelishiladi — taklif yuboring");
        }
        blocks.requireNone(me.getId(), o.getSender().getId(), "Bu buyurtmani ola olmaysiz");
        requireParcelSlot(me, o);

        Offer offer = offerRepository.findFirstByOrderIdAndDriverIdAndStatus(orderId, me.getId(), OfferStatus.PENDING)
                .orElseGet(Offer::new);
        offer.setOrder(o);
        offer.setDriver(me);
        // Narxsiz olish: taklif jadvalida narx majburiy — 0 yoziladi (yakuniy narx «kelishiladi» bo'lib qoladi)
        offer.setPrice(o.getPrice() != null ? o.getPrice() : 0L);
        offer.setComment(o.getPrice() != null ? "E'londagi narxga rozi bo'ldi" : "Oldi — narxni telefonda kelishadi");
        offerRepository.save(offer);
        assignDriver(o, offer, me);
        return toDto(o, me, true);
    }

    /** Yuboruvchi e'lonni shoshilinch qiladi: ro'yxatda eng yuqoriga chiqadi va haydovchilarga qayta xabar boradi. */
    @Transactional
    public OrderDto makeUrgent(Long orderId) {
        User me = currentUser.get();
        Order o = findForUpdate(orderId);
        requireSender(o, me);
        if (o.getStatus() != OrderStatus.NEW) throw ApiException.badRequest("Faqat haydovchi kutayotgan e'lonni shoshilinch qilish mumkin");
        if (o.isUrgent()) return toDto(o, me, true);
        if (o.getPickupDate() != null && o.getPickupDate().isBefore(LocalDate.now(TZ))) {
            throw ApiException.badRequest("Olib ketish sanasi o'tib ketgan e'lonni shoshilinch qilib bo'lmaydi. Yangi sana bilan qayta joylang.");
        }
        long fee = chargeUrgentFee(o, me);
        o.setUrgent(true);
        event(o, OrderStatus.NEW, "E'lon shoshilinch qilindi" + (fee > 0 ? " (" + money(fee) + ")" : ""), me);
        matches.onNewOrder(o, me);   // haydovchilarga qayta xabar
        channels.orderChanged(o.getId());   // kanal postiga 🔥 belgisi
        return toDto(o, me, true);
    }

    /** E'lon sanasini o'zgartirish (sanasi o'tgan e'lonni bugunga yoki ertaga): faqat egasi, faqat haydovchi kutayotganda */
    @Transactional
    public OrderDto reschedule(Long orderId, RescheduleRequest req) {
        User me = currentUser.get();
        return toDto(reschedule(me, orderId, req.date()), me, true);
    }

    /** Botdagi «📅 Bugunga» / «📅 Ertaga» (sanasi o'tgan e'lon haqidagi eslatmada) */
    @Transactional(noRollbackFor = ApiException.class)
    public Order rescheduleFromBot(User me, Long orderId, LocalDate date) {
        return reschedule(me, orderId, date);
    }

    private Order reschedule(User me, Long orderId, LocalDate date) {
        Order o = findForUpdate(orderId);
        requireSender(o, me);
        if (o.getStatus() != OrderStatus.NEW) {
            throw ApiException.badRequest("E'lonni haydovchi olgan yoki e'lon yopilgan — sanani o'zgartirib bo'lmaydi");
        }
        LocalDate today = LocalDate.now(TZ);
        if (date == null || date.isBefore(today)) throw ApiException.badRequest("Bugungi yoki keyingi sanani tanlang");
        if (date.isAfter(today.plusDays(90))) throw ApiException.badRequest("Sana juda uzoq — 3 oy ichidagi sanani tanlang");
        if (date.equals(o.getPickupDate())) return o;
        boolean wasLate = o.getPickupDate() != null && o.getPickupDate().isBefore(today);
        o.setPickupDate(date);
        event(o, OrderStatus.NEW, "Sana o'zgartirildi: " + date.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")), me);
        // Sanasi o'tib, bozorning oxiriga tushib qolgan e'lon yana yangidek: haydovchilarga qayta xabar
        if (wasLate) matches.onNewOrder(o, me);
        channels.orderChanged(o.getId());
        return o;
    }

    /** Daromad tizimi yoqilgan bo'lsa shoshilinch narxini yuboruvchidan yechadi. @return yechilgan summa */
    private long chargeUrgentFee(Order o, User sender) {
        // Bonus: har bir foydalanuvchining birinchi shoshilinch e'loni bepul. Yuboruvchining hamyon qatori bloklanadi —
        // bir vaqtda yuborilgan ikki shoshilinch e'lon ikkalasi ham "birinchi" bo'lib qolmasin. (Foydalanuvchi qatori emas:
        // hisobni o'chirish avval foydalanuvchini, keyin buyurtmalarni bloklaydi — teskari tartib tranzaksiyalarni to'xtatib qo'yardi)
        wallet.lockWallet(sender.getId());
        if (wallet.urgentFreeAvailable(sender, o.getId())) return 0;
        long fee = wallet.urgentFee();
        if (fee > 0) {
            wallet.charge(sender, fee, TxType.URGENT_FEE, o.getId(), "Shoshilinch e'lon #" + o.getId() + ": " + o.getTitle(),
                    "Shoshilinch e'lon narxi " + money(fee) + ". Balansingizda mablag' yetarli emas — balansni to'ldiring"
                            + " yoki \"Shoshilinch\" belgisini olib tashlang.");
        }
        return fee;
    }

    @Transactional
    public void withdrawOffer(Long orderId, Long offerId) {
        User me = currentUser.get();
        Offer offer = offerRepository.findById(offerId)
                .filter(f -> f.getOrder().getId().equals(orderId))
                .orElseThrow(() -> ApiException.notFound("Taklif topilmadi"));
        if (!offer.getDriver().getId().equals(me.getId())) throw ApiException.forbidden("Bu sizning taklifingiz emas");
        if (offer.getStatus() != OfferStatus.PENDING) throw ApiException.badRequest("Taklifni qaytarib bo'lmaydi");
        offer.setStatus(OfferStatus.WITHDRAWN);
        Order o = offer.getOrder();
        o.setOffersCount((int) offerRepository.countByOrderIdAndStatus(orderId, OfferStatus.PENDING));
    }

    @Transactional
    public void rejectOffer(Long orderId, Long offerId) {
        User me = currentUser.get();
        Order o = find(orderId);
        requireSender(o, me);
        Offer offer = offerRepository.findById(offerId)
                .filter(f -> f.getOrder().getId().equals(orderId))
                .orElseThrow(() -> ApiException.notFound("Taklif topilmadi"));
        if (offer.getStatus() != OfferStatus.PENDING) return;
        offer.setStatus(OfferStatus.REJECTED);
        o.setOffersCount((int) offerRepository.countByOrderIdAndStatus(orderId, OfferStatus.PENDING));
        notifications.notify(offer.getDriver(), OrderNotes.offerRejected(o), "#/orders/" + o.getId());
    }

    private void assignDriver(Order o, Offer accepted, User actor) {
        // Daromad tizimi yoqilgan bo'lsa — haydovchidan komissiya (yetmasa xato, hech narsa o'zgarmaydi)
        boolean byDriver = actor.getId().equals(accepted.getDriver().getId());
        // Yangi haydovchi bonusi: birinchi N ta buyurtmasidan komissiya olinmaydi. Haydovchining hamyon qatori bloklanadi —
        // bir vaqtda bir nechta buyurtma olib, bepul buyurtmalar sonidan oshirib bo'lmasin (foydalanuvchi qatori emas —
        // hisobni o'chirish bilan teskari tartibda bloklanib qolmasin)
        wallet.lockWallet(accepted.getDriver().getId());
        // Pochta chegarasi: hamyon qatori bloklangan — bir vaqtda ikkita pochta olib, chegaradan oshirib bo'lmaydi
        if (o.getKind() == OrderKind.PARCEL) {
            int cap = rules.parcelCap();
            if (cap > 0 && rules.activeParcels(accepted.getDriver().getId()) >= cap) {
                throw ApiException.badRequest(byDriver ? OrderRules.parcelCapMessage(cap)
                        : "Bu haydovchida hozir " + cap + " ta pochta bor (chegara) — u ularni yetkazgach qabul qila olasiz "
                        + "yoki boshqa taklifni tanlang");
            }
        }
        boolean freeRide = wallet.freeDeliveriesLeft(accepted.getDriver()) > 0;
        // Narxsiz olingan (pochta, yo'lovchi) e'lon: taklif narxi 0 — yakuniy narx «kelishiladi»
        Long agreed = accepted.getPrice() != null && accepted.getPrice() > 0 ? accepted.getPrice() : null;
        long fee = freeRide ? 0 : wallet.chargeCommission(accepted.getDriver(), o.getId(), agreed, o.getTitle(), byDriver);
        o.setCommission(fee > 0 ? fee : null);

        accepted.setStatus(OfferStatus.ACCEPTED);
        o.setDriver(accepted.getDriver());
        o.setFinalPrice(agreed);
        o.setStatus(OrderStatus.ACCEPTED);
        o.setAcceptedAt(Instant.now());
        for (Offer other : offerRepository.findByOrderIdAndStatus(o.getId(), OfferStatus.PENDING)) {
            if (other.getId().equals(accepted.getId())) continue;
            other.setStatus(OfferStatus.REJECTED);
            notifications.notify(other.getDriver(), OrderNotes.givenToOther(o), null);
        }
        o.setOffersCount(0);
        User driver = accepted.getDriver();
        event(o, OrderStatus.ACCEPTED, "Haydovchi tanlandi: " + driver.getFullName() + ", narx " + money(agreed), actor);
        channels.orderChanged(o.getId());   // kanalda: "✅ Haydovchi topildi"

        notifications.notify(driver, OrderNotes.assignedToDriver(o, agreed, fee), "#/orders/" + o.getId());
        if (!actor.getId().equals(o.getSender().getId())) {
            notifications.notify(o.getSender(), OrderNotes.driverFound(o, driver, agreed), "#/orders/" + o.getId());
        }
    }

    /** Pochta chegarasi (taklif yuborish va olishdan oldin — tushunarli xato bilan). Aniq tekshiruv — assignDriver'da */
    private void requireParcelSlot(User driver, Order o) {
        if (o.getKind() != OrderKind.PARCEL) return;
        int cap = rules.parcelCap();
        if (cap > 0 && rules.activeParcels(driver.getId()) >= cap) throw ApiException.badRequest(OrderRules.parcelCapMessage(cap));
    }

    /** Ro'yxat uchun DTO (masalan, «Reysga yig'ish»): ko'rayotgan odamga qarab maxfiy maydonlar yopiladi */
    public List<OrderDto> listDtos(List<Order> list, User viewer) {
        List<OrderDto> out = new ArrayList<>(list.size());
        for (Order o : list) out.add(toDto(o, viewer));
        return out;
    }

    // =====================================================================
    // Holat o'zgarishlari
    // =====================================================================

    /** Haydovchi yukni oldi — yo'lga chiqdi. */
    @Transactional
    public OrderDto pickUp(Long orderId) {
        User me = currentUser.get();
        Order o = findForUpdate(orderId);
        requireDriver(o, me);
        if (o.getStatus() != OrderStatus.ACCEPTED) throw ApiException.badRequest("Buyurtma holati mos emas");
        o.setStatus(OrderStatus.IN_TRANSIT);
        o.setPickedUpAt(Instant.now());
        if (o.isPassenger()) {
            event(o, OrderStatus.IN_TRANSIT, "Yo'lovchi olindi, yo'lga chiqildi", me);
            notifications.notify(o.getSender(), OrderNotes.pickedUp(o, me), "#/orders/" + o.getId());
            return toDto(o, me, true);
        }
        event(o, OrderStatus.IN_TRANSIT, (o.getKind() == OrderKind.PARCEL ? "Pochta" : "Yuk") + " olindi, haydovchi yo'lda", me);
        notifications.notify(o.getSender(), OrderNotes.pickedUp(o, me), "#/orders/" + o.getId());
        notifyReceiver(o);
        return toDto(o, me, true);
    }

    /** Qabul qiluvchi Telegramga ulangan bo'lsa unga ham xabar va kod yuboriladi. */
    private void notifyReceiver(Order o) {
        if (o.getReceiverPhone() == null || o.getReceiverPhone().equals(o.getSender().getPhone())) return;
        linkRepository.findById(o.getReceiverPhone()).ifPresent(link -> telegram.sendWithButton(link.getChatId(),
                "📦 <b>Sizga yuk kelyapti!</b>\n" + TelegramClient.esc(o.getTitle()) + "\n"
                        + "Yuboruvchi: " + TelegramClient.esc(o.getSender().getFullName()) + "\n"
                        + "Haydovchi: " + TelegramClient.esc(o.getDriver().getFullName()) + ", " + o.getDriver().getPhone() + "\n\n"
                        + "Yukni qabul qilganingizda haydovchiga shu kodni ayting: <code>" + o.getDeliveryCode() + "</code>",
                "Kuzatish", notifications.siteUrl("#/orders/" + o.getId())));
    }

    /** Haydovchi qabul qiluvchidan olingan kod bilan topshiradi. */
    @Transactional
    public OrderDto deliver(Long orderId, DeliverRequest req) {
        User me = currentUser.get();
        Order o = findForUpdate(orderId);
        requireDriver(o, me);
        boolean passenger = o.isPassenger();
        if (o.getStatus() != OrderStatus.IN_TRANSIT) {
            throw ApiException.badRequest(passenger ? "Avval yo'lovchini olganingizni belgilang" : "Avval yukni olganingizni belgilang");
        }
        // Yo'lovchi o'zi mashinada — kod kerak emas; yuk va pochta faqat qabul qiluvchidagi kod bilan topshiriladi
        String code = req == null || req.code() == null ? "" : req.code().trim();
        if (!passenger && !o.getDeliveryCode().equals(code)) {
            throw ApiException.badRequest(code.isEmpty() ? "Topshirish kodini kiriting"
                    : "Topshirish kodi noto'g'ri. Kodni qabul qiluvchidan so'rang");
        }
        // Ikki tomon qatori ID tartibida bloklanadi va bazadan qayta o'qiladi: bir vaqtda A B uchun, B A uchun yetkazsa ham
        // (taklif bonusi ikkala hisobni o'zgartiradi) tranzaksiyalar bir-birini kutib qolmaydi, bir haydovchining ikki
        // yetkazishi bir vaqtda tugasa ham hisoblagich yo'qolmaydi
        User senderRow = o.getSender();
        for (User party : me.getId() < senderRow.getId() ? List.of(me, senderRow) : List.of(senderRow, me)) locks.lockFresh(party);
        o.setStatus(OrderStatus.DELIVERED);
        o.setDeliveredAt(Instant.now());
        me.setDeliveriesCompleted(me.getDeliveriesCompleted() + 1);
        event(o, OrderStatus.DELIVERED, passenger ? "Manzilga yetib kelindi" : "Qabul qiluvchiga topshirildi", me);
        growth.onDelivered(o);   // taklif havolasi orqali kelganlarga birinchi yetkazishda bonus
        notifications.notify(o.getSender(), OrderNotes.deliveredToSender(o), "#/orders/" + o.getId());
        notifications.notify(me, OrderNotes.deliveredToDriver(o), "#/orders/" + o.getId());
        return toDto(o, me, true);
    }

    @Transactional
    public OrderDto cancel(Long orderId, CancelRequest req) {
        User me = currentUser.get();
        Order o = cancelBy(me, orderId, req == null ? null : trimToNull(req.reason()));
        return toDto(o, me, true);
    }

    /**
     * Telegram bot: e'lon egasi «❌ Bekor qilish» tugmasini bosdi. Faqat hali hech kim olmagan e'lon — haydovchi
     * olgan bo'lsa, bekor qilish saytda (sababi bilan) qilinadi.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Order cancelFromBot(User me, Long orderId) {
        // Avval qator bloklanadi va bazadagi joriy holat o'qiladi: shu payt haydovchi «Olaman» bosgan bo'lsa — biz ko'ramiz
        Order o = findForUpdate(orderId);
        if (!o.getSender().getId().equals(me.getId())) throw ApiException.forbidden("Bu sizning e'loningiz emas");
        if (o.getStatus() == OrderStatus.CANCELLED) return o;
        if (o.getStatus() != OrderStatus.NEW) {
            throw ApiException.badRequest("E'lonni haydovchi olgan — bekor qilish uchun saytda e'lonni oching");
        }
        return cancelBy(me, orderId, "Telegram orqali bekor qilindi");
    }

    Order cancelBy(User me, Long orderId, String reason) {
        Order o = findForUpdate(orderId);
        boolean sender = o.getSender().getId().equals(me.getId());
        boolean driver = o.getDriver() != null && o.getDriver().getId().equals(me.getId());

        if (sender) {
            if (o.getStatus() == OrderStatus.IN_TRANSIT) {
                throw ApiException.badRequest("Yuk yo'lda — bekor qilish uchun haydovchi bilan bog'laning yoki adminga murojaat qiling");
            }
            if (o.getStatus() != OrderStatus.NEW && o.getStatus() != OrderStatus.ACCEPTED) {
                throw ApiException.badRequest("Bu buyurtmani bekor qilib bo'lmaydi");
            }
            User assigned = o.getDriver();
            closeAsCancelled(o, reason, me);
            if (assigned != null) {
                notifications.notify(assigned, OrderNotes.cancelledBySender(o, reason), "#/orders/" + o.getId());
                refundCommission(o, assigned, "Yuboruvchi buyurtmani bekor qildi (#" + o.getId() + ")");
            }
        } else if (driver) {
            if (o.getStatus() != OrderStatus.ACCEPTED) {
                throw ApiException.badRequest("Yuk olingandan keyin voz kechib bo'lmaydi");
            }
            // Haydovchi voz kechdi — buyurtma qayta bozorga chiqadi
            offerRepository.findFirstByOrderIdAndDriverIdAndStatus(o.getId(), me.getId(), OfferStatus.ACCEPTED)
                    .ifPresent(f -> f.setStatus(OfferStatus.WITHDRAWN));
            o.setDriver(null);
            o.setFinalPrice(null);
            o.setAcceptedAt(null);
            o.setCommission(null);   // haydovchi o'zi voz kechdi — komissiya qaytarilmaydi
            // Uning yo'li va oxirgi joylashuvi keyingi haydovchinikiga aralashmasin (va endi yuboruvchiga ko'rinmasin)
            o.setDriverLat(null);
            o.setDriverLng(null);
            o.setDriverLocationAt(null);
            trackRepository.deleteByOrder(o.getId());
            o.setStatus(OrderStatus.NEW);
            me.setCancelledAsDriver(me.getCancelledAsDriver() + 1);
            event(o, OrderStatus.NEW, "Haydovchi voz kechdi" + (reason != null ? ": " + reason : "") + ". Buyurtma qayta e'lon qilindi", me);
            channels.orderChanged(o.getId());   // kanaldagi post yana ochiq e'longa qaytadi
            notifications.notify(o.getSender(), OrderNotes.driverWithdrew(o, me, reason), "#/orders/" + o.getId());
            matches.onNewOrder(o, o.getSender(), me.getId());   // e'lon qayta ochildi — mos haydovchilarga (voz kechganning o'zidan boshqa) xabar
        } else if (me.getRole().isStaff()) {
            adminCancel(o, reason, me);
        } else {
            throw ApiException.forbidden("Ruxsat yo'q");
        }
        return o;
    }

    /** Admin istalgan holatdagi (yakunlanmagan) buyurtmani bekor qiladi. */
    @Transactional
    public void adminCancel(Order o, String reason, User admin) {
        if (o.getStatus() == OrderStatus.DELIVERED || o.getStatus() == OrderStatus.CANCELLED) {
            throw ApiException.badRequest("Buyurtma allaqachon yakunlangan");
        }
        User driver = o.getDriver();
        // Komissiya faqat yuk olinmasdan oldin qaytariladi: yo'ldagi buyurtma bo'yicha nizoda admin o'zi qaror qiladi
        boolean beforePickup = o.getStatus() == OrderStatus.ACCEPTED;
        closeAsCancelled(o, "Admin tomonidan" + (reason != null ? ": " + reason : ""), admin);
        notifications.notify(o.getSender(), OrderNotes.cancelledByAdmin(o, reason), "#/orders/" + o.getId());
        if (driver != null) {
            notifications.notify(driver, OrderNotes.cancelledByAdmin(o, reason), "#/orders/" + o.getId());
            if (beforePickup) refundCommission(o, driver, "Admin buyurtmani bekor qildi (#" + o.getId() + ")");
        }
    }

    /**
     * Foydalanuvchi hisobini o'chirganda: ochiq e'lonlari yopiladi, kutilayotgan takliflari qaytarib olinadi.
     * Har bir buyurtma bloklanib, holati qayta tekshiriladi: shu soniyada kimdir e'lonni olgan yoki taklifni qabul
     * qilgan bo'lsa, o'chirish to'xtatiladi (aks holda faol buyurtmada "o'chirilgan", telefonsiz tomon qolardi).
     * Chaqiruvchining (UserService.deleteAccount) tranzaksiyasiga qo'shiladi — xato bo'lsa hammasi bekor bo'ladi.
     */
    @Transactional
    public void closeForDeletedAccount(User u) {
        Set<Long> own = new HashSet<>(orderRepository.findOpenOrderIdsBySender(u.getId()));
        Set<Long> offered = new HashSet<>(offerRepository.findOrderIdsWithPendingOffer(u.getId()));
        // Hammasi bitta ID tartibida bloklanadi — bir vaqtda ikki hisob o'chirilsa ham bir-birini kutib qolmasin (deadlock)
        List<Long> ids = new ArrayList<>(own);
        ids.addAll(offered);
        for (Long id : ids.stream().distinct().sorted().toList()) {
            Order o = findForUpdate(id);
            if (own.contains(id)) {
                if (o.getStatus() == OrderStatus.NEW) {
                    closeAsCancelled(o, "Yuboruvchi hisobini o'chirdi", null);
                } else if (o.getStatus().isActive()) {
                    throw ApiException.conflict("\"" + o.getTitle() + "\" ni hozirgina haydovchi oldi. Avval shu buyurtmani yakunlang yoki bekor qiling");
                }
            }
            if (offered.contains(id)) {
                if (o.getStatus().isActive() && o.getDriver() != null && o.getDriver().getId().equals(u.getId())) {
                    throw ApiException.conflict("\"" + o.getTitle() + "\" bo'yicha taklifingiz hozirgina qabul qilindi. "
                            + "Avval shu buyurtmani yakunlang yoki undan voz keching");
                }
                offerRepository.findFirstByOrderIdAndDriverIdAndStatus(id, u.getId(), OfferStatus.PENDING)
                        .ifPresent(f -> f.setStatus(OfferStatus.WITHDRAWN));
                o.setOffersCount((int) offerRepository.countByOrderIdAndStatus(id, OfferStatus.PENDING));
            }
        }
    }

    /**
     * Hisob o'chirilganda buyurtmalar tarixidan shaxsiy ma'lumotlarni olib tashlaydi: haydovchi sifatidagi GPS
     * yo'llari va oxirgi joylashuvi o'chiriladi, tarixdagi (timeline) ismi {@code newName} ga almashtiriladi.
     * Buyurtmalarning o'zi (narx, yo'nalish, holat) ikkinchi tomon va hisob-kitob uchun qoladi.
     */
    @Transactional
    public void anonymizeHistory(User u, String oldName, String newName) {
        trackRepository.deleteByDriver(u.getId());
        readRepository.deleteAllForUser(u.getId());
        orderRepository.clearDriverLocation(u.getId());
        eventRepository.renameActor(u.getId(), newName);
        if (oldName == null || oldName.isBlank()) return;
        // "Haydovchi tanlandi: <ism>, narx ..." — faqat aynan shu yozuv va faqat u taklif bergan buyurtmalarda
        // (boshqa haydovchining "Aziz Karimov" ismi "Aziz Karim" o'chirilganda buzilmasin)
        String prefix = "Haydovchi tanlandi: " + oldName + ", ";
        List<Long> ids = offerRepository.findOrderIdsOfferedBy(u.getId());
        for (int i = 0; i < ids.size(); i += 500) {
            for (OrderEvent e : eventRepository.findByOrderIdIn(ids.subList(i, Math.min(ids.size(), i + 500)))) {
                if (e.getText() != null && e.getText().startsWith(prefix)) {
                    String text = "Haydovchi tanlandi: " + newName + ", " + e.getText().substring(prefix.length());
                    e.setText(text.length() > 300 ? text.substring(0, 300) : text);
                }
            }
        }
    }

    /** Bekor qilingan buyurtma uchun haydovchidan yechilgan komissiyani qaytaradi (bo'lsa). */
    private void refundCommission(Order o, User driver, String reason) {
        Long fee = o.getCommission();
        if (driver == null || fee == null || fee <= 0) return;
        wallet.refundCommission(driver.getId(), o.getId(), fee, reason);
        o.setCommission(null);
        notifications.notify(driver, OrderNotes.commissionRefunded(o, fee), "#/profile");
    }

    private void closeAsCancelled(Order o, String reason, User actor) {
        o.setStatus(OrderStatus.CANCELLED);
        o.setCancelledAt(Instant.now());
        o.setCancelReason(reason != null && reason.length() > 300 ? reason.substring(0, 300) : reason);
        for (Offer f : offerRepository.findByOrderIdAndStatus(o.getId(), OfferStatus.PENDING)) {
            f.setStatus(OfferStatus.REJECTED);
        }
        o.setOffersCount(0);
        event(o, OrderStatus.CANCELLED, "Buyurtma bekor qilindi" + (reason != null ? ": " + reason : ""), actor);
        channels.orderChanged(o.getId());   // kanalda: "❌ E'lon yopilgan"
    }

    // =====================================================================
    // Kuzatuv (GPS) va yozishma
    // =====================================================================

    @Transactional
    public void track(Long orderId, TrackRequest req) {
        User me = currentUser.get();
        Order o = find(orderId);
        requireDriver(o, me);
        if (!o.getStatus().isActive()) return;
        Instant now = Instant.now();
        // Juda tez-tez kelgan nuqtalarni bazaga yozmaymiz (5 soniyada 1 marta)
        boolean store = o.getDriverLocationAt() == null || o.getDriverLocationAt().plusSeconds(5).isBefore(now);
        o.setDriverLat(req.lat());
        o.setDriverLng(req.lng());
        o.setDriverLocationAt(now);
        me.setLastLat(req.lat());
        me.setLastLng(req.lng());
        me.setLastLocationAt(now);
        if (store) {
            TrackPoint p = new TrackPoint();
            p.setOrderId(orderId);
            p.setLat(req.lat());
            p.setLng(req.lng());
            p.setSpeedKmh(req.speedKmh());
            p.setHeading(req.heading());
            trackRepository.save(p);
        }
    }

    @Transactional(readOnly = true)
    public TrackDto trackInfo(Long orderId) {
        User me = currentUser.get();
        Order o = find(orderId);
        if (!isParticipant(o, me) && !me.getRole().isStaff()) throw ApiException.forbidden("Ruxsat yo'q");
        List<TrackPointDto> path = new ArrayList<>(trackRepository
                .findByOrderIdOrderByCreatedAtDesc(orderId, PageRequest.of(0, 1000)).stream()
                .map(TrackPointDto::of).toList());
        Collections.reverse(path);
        return new TrackDto(path.isEmpty() ? null : path.get(path.size() - 1), path, o.getStatus());
    }

    @Transactional(readOnly = true)
    public List<EventDto> events(Long orderId) {
        User me = currentUser.get();
        Order o = find(orderId);
        if (!isParticipant(o, me) && !me.getRole().isStaff()) throw ApiException.forbidden("Ruxsat yo'q");
        return eventRepository.findByOrderIdOrderByCreatedAtAsc(orderId).stream().map(EventDto::of).toList();
    }

    /**
     * Yozishma: har bir haydovchi bilan alohida ip. Haydovchi — o'z ipini, e'lon egasi — tanlagan haydovchi ipini
     * (driverId; berilmasa — e'lon berilgan haydovchi bilan), admin — istalganini ko'radi (faqat o'qish).
     * E'lon shu haydovchiga berilmaguncha matndagi telefon raqamlar yopiq ko'rinadi.
     */
    @Transactional
    public List<MessageDto> messages(Long orderId, Long driverId, Long afterId) {
        User me = currentUser.get();
        Order o = find(orderId);
        boolean isSender = o.getSender().getId().equals(me.getId());
        boolean staff = me.getRole().isStaff();
        Long thread = (isSender || staff) && driverId != null ? driverId
                : isSender || (staff && !isParticipant(o, me)) ? assignedId(o) : me.getId();
        if (thread == null) return List.of();
        boolean assigned = thread.equals(assignedId(o));
        List<OrderMessage> list = messageRepository.thread(orderId, thread, afterId == null ? 0 : afterId);
        // Yozishma tomoni o'qidi — «Xabarlar» dagi o'qilmaganlar soni kamayadi (admin ko'rishi hisobga olinmaydi)
        if (!list.isEmpty() && (isSender || thread.equals(me.getId()))) {
            markRead(me.getId(), orderId, thread, list.get(list.size() - 1).getId());
        }
        return list.stream().map(m -> MessageDto.of(m, !assigned && !staff)).toList();
    }

    private void markRead(Long userId, Long orderId, Long driverId, long lastId) {
        MessageRead r = readRepository.findFirstByUserIdAndOrderIdAndDriverIdOrderByIdAsc(userId, orderId, driverId).orElse(null);
        if (r == null) {
            r = new MessageRead();
            r.setUserId(userId);
            r.setOrderId(orderId);
            r.setDriverId(driverId);
            r.setLastReadId(lastId);
            readRepository.save(r);
        } else if (r.getLastReadId() < lastId) {
            r.setLastReadId(lastId);
        }
    }

    @Transactional
    public MessageDto sendMessage(Long orderId, MessageRequest req) {
        User me = currentUser.get();
        Terms.require(me);
        Order o = find(orderId);
        boolean isSender = o.getSender().getId().equals(me.getId());
        Long assignedId = assignedId(o);
        Long thread;
        User other;
        if (isSender) {
            thread = req.driverId() != null ? req.driverId() : assignedId;
            if (thread == null) throw ApiException.badRequest("Qaysi haydovchiga yozayotganingizni tanlang");
            if (!thread.equals(assignedId)) {
                if (assignedId != null || o.getStatus() != OrderStatus.NEW) {
                    throw ApiException.badRequest("E'lon boshqa haydovchiga berilgan yoki yopilgan — bu yozishma yopiq");
                }
                // E'lon egasi faqat o'ziga yozgan yoki narx taklif qilgan haydovchiga yoza oladi
                if (!messageRepository.existsByOrderIdAndDriverId(orderId, thread)
                        && offerRepository.findFirstByOrderIdAndDriverIdAndStatus(orderId, thread, OfferStatus.PENDING).isEmpty()) {
                    throw ApiException.badRequest("Bu haydovchi bilan yozishma yo'q");
                }
            }
            other = thread.equals(assignedId) ? o.getDriver()
                    : userRepository.findById(thread).orElseThrow(() -> ApiException.notFound("Haydovchi topilmadi"));
        } else {
            thread = me.getId();
            if (!thread.equals(assignedId)) {
                if (assignedId != null || o.getStatus() != OrderStatus.NEW) {
                    throw ApiException.badRequest("E'lon boshqa haydovchiga berilgan yoki yopilgan");
                }
                if (!me.isDriver()) {
                    throw ApiException.badRequest("E'lon egasiga yozish uchun profilingizga mashina ma'lumotlarini kiriting");
                }
                if (me.getPhone() == null) throw ApiException.badRequest("Telefon raqam tasdiqlanmagan");
            }
            other = o.getSender();
        }
        blocks.requireNone(me.getId(), other.getId(), "Bu foydalanuvchi bilan yozishma cheklangan");
        boolean assigned = thread.equals(assignedId);

        OrderMessage m = new OrderMessage();
        m.setOrderId(orderId);
        m.setDriverId(thread);
        m.setAuthor(me);
        m.setText(req.text().trim());
        messageRepository.save(m);

        // Xabar faqat yozishmada turadi — Telegramga ham, 🔔 ga ham yuborilmaydi: ikkinchi tomon uni saytdagi «💬 Xabarlar»da
        // (qizil belgi) ko'radi, Android ilovasi bo'lsa — telefoniga push keladi (haydovchi tanlanmaguncha raqam yopiq)
        notifications.push(other, "💬 " + me.getFullName(), assigned ? m.getText() : Phones.maskInText(m.getText()),
                "#/orders/" + orderId + "?chat=" + thread);
        return MessageDto.of(m, !assigned);
    }

    /**
     * E'lon egasi uchun: qaysi haydovchilar yozgan (har biri alohida ip). Bloklangan haydovchilar ko'rsatilmaydi,
     * telefonlar berilmaydi.
     */
    @Transactional(readOnly = true)
    public List<ThreadDto> threads(Long orderId) {
        User me = currentUser.get();
        Order o = find(orderId);
        boolean staff = me.getRole().isStaff();
        if (!o.getSender().getId().equals(me.getId()) && !staff) throw ApiException.forbidden("Ruxsat yo'q");
        Long assignedId = assignedId(o);
        Map<Long, List<OrderMessage>> byDriver = new LinkedHashMap<>();
        for (OrderMessage m : messageRepository.findByOrderIdAndIdGreaterThanOrderByIdAsc(orderId, 0L)) {
            if (m.getDriverId() != null) byDriver.computeIfAbsent(m.getDriverId(), k -> new ArrayList<>()).add(m);
        }
        if (byDriver.isEmpty()) return List.of();
        Map<Long, User> drivers = new HashMap<>();
        userRepository.findAllById(byDriver.keySet()).forEach(u -> drivers.put(u.getId(), u));
        List<ThreadDto> out = new ArrayList<>();
        byDriver.forEach((driverId, list) -> {
            User d = drivers.get(driverId);
            if (d == null) return;
            if (!staff && blocks.state(me.getId(), driverId) != null) return;
            OrderMessage last = list.get(list.size() - 1);
            boolean assigned = driverId.equals(assignedId);
            boolean open = assigned || (assignedId == null && o.getStatus() == OrderStatus.NEW);
            String text = assigned || staff ? last.getText() : Phones.maskInText(last.getText());
            out.add(new ThreadDto(driverId, UserBrief.of(d, false), list.size(),
                    text.length() > 120 ? text.substring(0, 119) + "…" : text, last.getCreatedAt(),
                    last.getAuthor().getId().equals(me.getId()), assigned, open));
        });
        out.sort(Comparator.comparing(ThreadDto::assigned).reversed()
                .thenComparing(ThreadDto::lastAt, Comparator.reverseOrder()));
        return out;
    }

    private static Long assignedId(Order o) {
        return o.getDriver() == null ? null : o.getDriver().getId();
    }

    // =====================================================================
    // Narx hisoblagich
    // =====================================================================

    public EstimateDto estimate(double distanceKm, Double weightKg, DeliveryScope scope) {
        var p = props.pricing();
        double w = weightKg == null ? 10 : weightKg;
        long perKm = w <= 50 ? p.perKmLight() : w <= 500 ? p.perKmMedium() : w <= 3000 ? p.perKmHeavy() : p.perKmTruck();
        long base = scope == DeliveryScope.CITY ? p.cityBase() : p.intercityBase();
        long suggested = roundTo(base + Math.round(Math.max(0, distanceKm) * perKm), 1000);
        return new EstimateDto(suggested, roundTo((long) (suggested * 0.8), 1000), roundTo((long) (suggested * 1.25), 1000),
                scope == null ? DeliveryScope.INTERREGION : scope);
    }

    // =====================================================================
    // Avtomatik tozalash: 30 kundan beri hech kim olmagan e'lonlarni yopish
    // =====================================================================

    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void expireStaleOrders() {
        var stale = orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.NEW, Instant.now().minus(Duration.ofDays(30)));
        for (Order o : stale) {
            closeAsCancelled(o, "30 kun ichida haydovchi topilmadi (avtomatik)", null);
        }
        if (!stale.isEmpty()) log.info("{} ta eskirgan buyurtma yopildi", stale.size());

        // Olib ketish sanasidan bir haftadan ko'p o'tgan va hali hech kim olmagan e'lonlar — bozorni toza saqlash uchun
        LocalDate limit = LocalDate.now(TZ).minusDays(7);
        int closed = 0;
        for (Order o : orderRepository.findByStatusAndPickupDateBefore(OrderStatus.NEW, limit)) {
            if (o.getStatus() != OrderStatus.NEW) continue;   // yuqorida 30 kunlik qoida bilan yopilgan
            closeAsCancelled(o, "Olib ketish sanasi o'tib ketdi (avtomatik)", null);
            notifications.notify(o.getSender(), OrderNotes.autoClosed(o), "#/new?copy=" + o.getId());
            closed++;
        }
        if (closed > 0) log.info("{} ta muddati o'tgan e'lon yopildi", closed);
    }

    // =====================================================================
    // Yordamchi
    // =====================================================================

    public Order find(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> ApiException.notFound("Buyurtma topilmadi"));
    }

    /**
     * Holatni o'zgartiruvchi amallar uchun: qatorni tranzaksiya oxirigacha bloklaydi, shuning uchun ikki haydovchi
     * bir vaqtda "olish"ni bossa ham buyurtma bittasiga beriladi va komissiya faqat undan yechiladi.
     * Faqat yozuvchi (readOnly bo'lmagan) tranzaksiyalarda chaqiring.
     */
    public Order findForUpdate(Long id) {
        return orderRepository.lockById(id).orElseThrow(() -> ApiException.notFound("Buyurtma topilmadi"));
    }

    private void event(Order o, OrderStatus status, String text, User actor) {
        OrderEvent e = new OrderEvent();
        e.setOrderId(o.getId());
        e.setStatus(status);
        e.setText(text.length() > 300 ? text.substring(0, 300) : text);
        if (actor != null) {
            e.setActorId(actor.getId());
            e.setActorName(actor.getFullName());
        } else {
            e.setActorName("Tizim");
        }
        eventRepository.save(e);
    }

    static DeliveryScope scopeOf(Region fr, District fd, Region tr, District td) {
        if (!fr.getId().equals(tr.getId())) return DeliveryScope.INTERREGION;
        if (fd != null && td != null && fd.getId().equals(td.getId())) return DeliveryScope.CITY;
        return fd == null || td == null ? DeliveryScope.CITY : DeliveryScope.INTERCITY;
    }

    private boolean isParticipant(Order o, User u) {
        if (u == null) return false;
        return o.getSender().getId().equals(u.getId()) || (o.getDriver() != null && o.getDriver().getId().equals(u.getId()));
    }

    private void requireSender(Order o, User me) {
        if (!o.getSender().getId().equals(me.getId())) throw ApiException.forbidden("Bu amal faqat yuboruvchi uchun");
    }

    private void requireDriver(Order o, User me) {
        if (o.getDriver() == null || !o.getDriver().getId().equals(me.getId())) {
            throw ApiException.forbidden("Bu amal faqat shu buyurtma haydovchisi uchun");
        }
    }

    private void requireDriverProfile(User me) {
        if (!me.isDriver()) throw ApiException.badRequest("Buyurtma olish uchun profilingizga mashina ma'lumotlarini kiriting");
        if (me.getPhone() == null) throw ApiException.badRequest("Telefon raqam tasdiqlanmagan");
    }

    private User currentUserOrNull() {
        Long id = currentUser.idOrNull();
        return id == null ? null : userRepository.findById(id).orElse(null);
    }

    public OrderDto toDtoForAdmin(Order o, User admin) {
        return toDto(o, admin);
    }

    OrderDto toDto(Order o, User viewer) {
        return toDto(o, viewer, false);
    }

    /** @param withBlock buyurtma sahifasi uchun: tomonlar o'rtasida blok bormi (yozishma yopiladi) — ro'yxatlarda hisoblanmaydi */
    OrderDto toDto(Order o, User viewer, boolean withBlock) {
        boolean isSender = viewer != null && o.getSender().getId().equals(viewer.getId());
        boolean isDriver = viewer != null && o.getDriver() != null && o.getDriver().getId().equals(viewer.getId());
        boolean isStaff = viewer != null && viewer.getRole().isStaff();
        String viewerRole = isSender ? "SENDER" : isDriver ? "DRIVER" : isStaff ? "STAFF" : "GUEST";
        // Telefonlar faqat buyurtma tomonlariga: yuboruvchi raqami haydovchiga buyurtma unga berilgandagina ochiladi
        // (aks holda platformani chetlab, komissiyasiz kelishib olish mumkin bo'lardi)
        boolean seeContacts = isSender || isDriver || isStaff;
        // Aniq manzil: tomonlarga va ochiq e'lonni ko'rayotgan kirgan foydalanuvchiga (mehmonga — faqat viloyat va tuman)
        boolean seeExactPlace = seeContacts || (o.getStatus() == OrderStatus.NEW && viewer != null);

        OfferDto myOffer = null;
        if (viewer != null && !isSender && viewer.isDriver() && o.getStatus() == OrderStatus.NEW) {
            myOffer = offerRepository.findFirstByOrderIdAndDriverIdAndStatus(o.getId(), viewer.getId(), OfferStatus.PENDING)
                    .map(OfferDto::of).orElse(null);
        }
        Boolean reviewed = null;
        if (o.getStatus() == OrderStatus.DELIVERED && (isSender || isDriver)) {
            reviewed = reviewRepository.existsByOrderIdAndAuthorId(o.getId(), viewer.getId());
        }
        String block = null;
        if (withBlock && (isSender || isDriver) && o.getDriver() != null) {
            String s = blocks.state(viewer.getId(), isSender ? o.getDriver().getId() : o.getSender().getId());
            // Kim bloklaganini oshkor qilmaymiz: o'zim bloklagan bo'lsam — "ME" (blokdan chiqarish mumkin), aks holda "CLOSED"
            block = s == null ? null : BlockService.BY_ME.equals(s) ? "ME" : "CLOSED";
        }
        // Sarlavha yoki izohga yozilgan telefon raqam ham haydovchi tanlanguncha yopiq (raqam faqat tomonlarga)
        return new OrderDto(
                o.getId(), seeContacts ? o.getTitle() : Phones.maskInText(o.getTitle()), o.getCargoType(), o.getWeightKg(),
                o.getVolumeM3(), seeContacts ? o.getDescription() : Phones.maskInText(o.getDescription()),
                o.getPhotoUrl(), o.getRequiredBodyType(),
                place(o.getFromRegion(), o.getFromDistrict(), seeExactPlace ? o.getFromAddress() : null,
                        seeExactPlace ? o.getFromLat() : null, seeExactPlace ? o.getFromLng() : null),
                place(o.getToRegion(), o.getToDistrict(), seeExactPlace ? o.getToAddress() : null,
                        seeExactPlace ? o.getToLat() : null, seeExactPlace ? o.getToLng() : null),
                o.getDistanceKm(), o.getDurationMin(), o.getScope(), o.getPickupDate(),
                o.getPrice(), o.getFinalPrice(), o.getPaymentMethod(), o.getPayer(), o.isUrgent(),
                o.getStatus(), UserBrief.of(o.getSender(), seeContacts), UserBrief.of(o.getDriver(), seeContacts),
                seeContacts ? o.getReceiverName() : null,
                seeContacts ? o.getReceiverPhone() : null,
                isSender || isStaff ? o.getDeliveryCode() : null,
                o.getOffersCount(), o.getViewsCount(),
                o.getCreatedAt(), o.getAcceptedAt(), o.getPickedUpAt(), o.getDeliveredAt(),
                o.getCancelledAt(), o.getCancelReason(),
                viewerRole, myOffer, reviewed,
                seeContacts ? o.getDriverLat() : null, seeContacts ? o.getDriverLng() : null,
                seeContacts ? o.getDriverLocationAt() : null,
                isDriver || isStaff ? o.getCommission() : null,
                block, o.getKind(), o.getSeats());
    }

    private static PlaceDto place(Region r, District d, String address, Double lat, Double lng) {
        return new PlaceDto(r.getId(), r.getName(), d == null ? null : d.getId(), d == null ? null : d.getName(),
                address, lat, lng);
    }

    static String money(Long v) {
        if (v == null) return "kelishiladi";
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.FRANCE);
        return nf.format(v).replace(' ', ' ').replace(' ', ' ') + " so'm";
    }

    private static long roundTo(long v, long step) {
        return Math.max(step, Math.round((double) v / step) * step);
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
