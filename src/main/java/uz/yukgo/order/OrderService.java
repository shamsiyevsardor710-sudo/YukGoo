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
import uz.yukgo.user.BodyType;
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

    // =====================================================================
    // Yaratish va ko'rish
    // =====================================================================

    @Transactional
    public OrderDto create(CreateOrderRequest req) {
        User me = currentUser.get();
        Region fromRegion = locationService.region(req.from().regionId());
        Region toRegion = locationService.region(req.to().regionId());
        District fromDistrict = req.from().districtId() == null ? null : locationService.district(req.from().districtId(), fromRegion);
        District toDistrict = req.to().districtId() == null ? null : locationService.district(req.to().districtId(), toRegion);
        if (req.pickupDate() != null && req.pickupDate().isBefore(LocalDate.now(TZ))) {
            throw ApiException.badRequest("Yukni olish sanasi o'tib ketgan — bugungi yoki keyingi sanani tanlang");
        }

        Order o = new Order();
        o.setSender(me);
        o.setTitle(req.title().trim());
        o.setCargoType(req.cargoType());
        o.setWeightKg(req.weightKg());
        o.setVolumeM3(req.volumeM3());
        o.setDescription(trimToNull(req.description()));
        o.setRequiredBodyType(req.requiredBodyType());
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
        o.setPickupDate(req.pickupDate());
        o.setPrice(req.price());
        o.setPaymentMethod(req.paymentMethod() == null ? PaymentMethod.CASH : req.paymentMethod());
        o.setPayer(req.payer() == null ? Payer.SENDER : req.payer());
        o.setUrgent(req.urgent());
        o.setReceiverName(req.receiverName().trim());
        o.setReceiverPhone(Phones.normalize(req.receiverPhone()));
        o.setDeliveryCode(String.format("%04d", RANDOM.nextInt(10_000)));
        orderRepository.save(o);
        if (o.isUrgent()) chargeUrgentFee(o, me);

        me.setOrdersCreated(me.getOrdersCreated() + 1);
        event(o, OrderStatus.NEW, "Buyurtma e'lon qilindi", me);
        notifyDriversAboutNewOrder(o, me);
        channels.orderChanged(o.getId());   // YukGo va hamkor Telegram kanallariga e'lon (saqlangandan keyin)
        return toDto(o, me);
    }

    @Transactional
    public OrderDto uploadPhoto(Long orderId, MultipartFile file) {
        User me = currentUser.get();
        Order o = find(orderId);
        requireSender(o, me);
        if (o.getStatus() != OrderStatus.NEW) throw ApiException.badRequest("Rasmni faqat yangi buyurtmaga qo'shish mumkin");
        String url = files.storeImage(file, "orders");
        files.delete(o.getPhotoUrl());
        o.setPhotoUrl(url);
        return toDto(o, me);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderDto> market(Long fromRegion, Long toRegion, CargoType cargoType, DeliveryScope scope,
                                         BodyType bodyType, Double maxWeight, String q, int page) {
        User viewer = currentUserOrNull();
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        var result = orderRepository.market(fromRegion, toRegion, cargoType, scope, bodyType, maxWeight, like,
                LocalDate.now(TZ), PageRequest.of(Math.max(0, page), 20));
        return PageResponse.of(result, o -> toDto(o, viewer));
    }

    @Transactional
    public OrderDto get(Long id) {
        User viewer = currentUser.get();
        Order o = find(id);
        if (!isParticipant(o, viewer)) o.setViewsCount(o.getViewsCount() + 1);
        return toDto(o, viewer);
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
        var result = "driver".equals(role)
                ? orderRepository.findByDriver(me.getId(), statuses, pageable)
                : orderRepository.findBySender(me.getId(), statuses, pageable);
        return PageResponse.of(result, o -> toDto(o, me));
    }

    // =====================================================================
    // Takliflar
    // =====================================================================

    @Transactional
    public OfferDto makeOffer(Long orderId, OfferRequest req) {
        User me = currentUser.get();
        Order o = find(orderId);
        requireDriverProfile(me);
        if (o.getSender().getId().equals(me.getId())) throw ApiException.badRequest("O'z buyurtmangizga taklif bera olmaysiz");
        if (o.getStatus() != OrderStatus.NEW) throw ApiException.badRequest("Bu buyurtma endi taklif qabul qilmaydi");
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

        notifications.notify(o.getSender(),
                isNew ? "Yangi taklif: " + money(req.price()) : "Taklif yangilandi: " + money(req.price()),
                me.getFullName() + " (⭐ " + me.getRatingAvg() + ", " + me.getDeliveriesCompleted() + " ta yetkazish) — \""
                        + o.getTitle() + "\"",
                "#/orders/" + o.getId());
        return OfferDto.of(offer);
    }

    @Transactional(readOnly = true)
    public List<OfferDto> offers(Long orderId) {
        User me = currentUser.get();
        Order o = find(orderId);
        boolean sender = o.getSender().getId().equals(me.getId());
        return offerRepository.findByOrderIdOrderByPriceAscCreatedAtAsc(orderId).stream()
                .filter(f -> sender || me.getRole().isStaff() || f.getDriver().getId().equals(me.getId()))
                .filter(f -> f.getStatus() != OfferStatus.WITHDRAWN || !sender)
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
        assignDriver(o, offer, me);
        return toDto(o, me);
    }

    /** Haydovchi e'londagi narxga darhol rozi bo'lib buyurtmani oladi. */
    @Transactional
    public OrderDto take(Long orderId) {
        User me = currentUser.get();
        Order o = findForUpdate(orderId);
        requireDriverProfile(me);
        if (o.getSender().getId().equals(me.getId())) throw ApiException.badRequest("O'z buyurtmangizni ola olmaysiz");
        if (o.getStatus() != OrderStatus.NEW) throw ApiException.badRequest("Buyurtma allaqachon band qilingan");
        if (o.getPrice() == null) throw ApiException.badRequest("Bu buyurtmada narx kelishiladi — taklif yuboring");

        Offer offer = offerRepository.findFirstByOrderIdAndDriverIdAndStatus(orderId, me.getId(), OfferStatus.PENDING)
                .orElseGet(Offer::new);
        offer.setOrder(o);
        offer.setDriver(me);
        offer.setPrice(o.getPrice());
        offer.setComment("E'londagi narxga rozi bo'ldi");
        offerRepository.save(offer);
        assignDriver(o, offer, me);
        return toDto(o, me);
    }

    /** Yuboruvchi e'lonni shoshilinch qiladi: ro'yxatda eng yuqoriga chiqadi va haydovchilarga qayta xabar boradi. */
    @Transactional
    public OrderDto makeUrgent(Long orderId) {
        User me = currentUser.get();
        Order o = findForUpdate(orderId);
        requireSender(o, me);
        if (o.getStatus() != OrderStatus.NEW) throw ApiException.badRequest("Faqat haydovchi kutayotgan e'lonni shoshilinch qilish mumkin");
        if (o.isUrgent()) return toDto(o, me);
        if (o.getPickupDate() != null && o.getPickupDate().isBefore(LocalDate.now(TZ))) {
            throw ApiException.badRequest("Olib ketish sanasi o'tib ketgan e'lonni shoshilinch qilib bo'lmaydi. Yangi sana bilan qayta joylang.");
        }
        long fee = chargeUrgentFee(o, me);
        o.setUrgent(true);
        event(o, OrderStatus.NEW, "E'lon shoshilinch qilindi" + (fee > 0 ? " (" + money(fee) + ")" : ""), me);
        notifyDriversAboutNewOrder(o, me);
        channels.orderChanged(o.getId());   // kanal postiga 🔥 belgisi
        return toDto(o, me);
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
        notifications.notify(offer.getDriver(), "Taklifingiz rad etildi",
                "\"" + o.getTitle() + "\" buyurtmasi bo'yicha taklifingiz qabul qilinmadi", "#/orders/" + o.getId());
    }

    private void assignDriver(Order o, Offer accepted, User actor) {
        // Daromad tizimi yoqilgan bo'lsa — haydovchidan komissiya (yetmasa xato, hech narsa o'zgarmaydi)
        boolean byDriver = actor.getId().equals(accepted.getDriver().getId());
        // Yangi haydovchi bonusi: birinchi N ta buyurtmasidan komissiya olinmaydi. Haydovchining hamyon qatori bloklanadi —
        // bir vaqtda bir nechta buyurtma olib, bepul buyurtmalar sonidan oshirib bo'lmasin (foydalanuvchi qatori emas —
        // hisobni o'chirish bilan teskari tartibda bloklanib qolmasin)
        wallet.lockWallet(accepted.getDriver().getId());
        boolean freeRide = wallet.freeDeliveriesLeft(accepted.getDriver()) > 0;
        long fee = freeRide ? 0 : wallet.chargeCommission(accepted.getDriver(), o.getId(), accepted.getPrice(), o.getTitle(), byDriver);
        o.setCommission(fee > 0 ? fee : null);

        accepted.setStatus(OfferStatus.ACCEPTED);
        o.setDriver(accepted.getDriver());
        o.setFinalPrice(accepted.getPrice());
        o.setStatus(OrderStatus.ACCEPTED);
        o.setAcceptedAt(Instant.now());
        for (Offer other : offerRepository.findByOrderIdAndStatus(o.getId(), OfferStatus.PENDING)) {
            if (other.getId().equals(accepted.getId())) continue;
            other.setStatus(OfferStatus.REJECTED);
            notifications.notify(other.getDriver(), "Buyurtma boshqa haydovchiga berildi",
                    "\"" + o.getTitle() + "\" — boshqa taklif tanlandi", null);
        }
        o.setOffersCount(0);
        User driver = accepted.getDriver();
        event(o, OrderStatus.ACCEPTED, "Haydovchi tanlandi: " + driver.getFullName() + ", narx " + money(accepted.getPrice()), actor);
        channels.orderChanged(o.getId());   // kanalda: "✅ Haydovchi topildi"

        notifications.notify(driver, "✅ Buyurtma sizga berildi!",
                "\"" + o.getTitle() + "\" — " + o.getFromRegion().getName() + " → " + o.getToRegion().getName()
                        + ", narx " + money(accepted.getPrice()) + ". Yuboruvchi: " + o.getSender().getFullName()
                        + ", " + o.getSender().getPhone()
                        + (fee > 0 ? ". Balansingizdan " + money(fee) + " komissiya yechildi" : ""),
                "#/orders/" + o.getId());
        if (!actor.getId().equals(o.getSender().getId())) {
            notifications.notify(o.getSender(), "Haydovchi buyurtmangizni oldi",
                    driver.getFullName() + " (" + driver.getPhone() + ") \"" + o.getTitle() + "\" ni olib ketadi",
                    "#/orders/" + o.getId());
        }
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
        event(o, OrderStatus.IN_TRANSIT, "Yuk olindi, haydovchi yo'lda", me);
        notifications.notify(o.getSender(), "🚚 Yukingiz yo'lda",
                me.getFullName() + " \"" + o.getTitle() + "\" ni oldi. Xaritada kuzatishingiz mumkin. "
                        + "Qabul qiluvchiga topshirish kodini ayting: " + o.getDeliveryCode(),
                "#/orders/" + o.getId());
        notifyReceiver(o);
        return toDto(o, me);
    }

    /** Qabul qiluvchi Telegramga ulangan bo'lsa unga ham xabar va kod yuboriladi. */
    private void notifyReceiver(Order o) {
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
        if (o.getStatus() != OrderStatus.IN_TRANSIT) throw ApiException.badRequest("Avval yukni olganingizni belgilang");
        if (!o.getDeliveryCode().equals(req.code().trim())) {
            throw ApiException.badRequest("Topshirish kodi noto'g'ri. Kodni qabul qiluvchidan so'rang");
        }
        // Ikki tomon qatori ID tartibida bloklanadi va bazadan qayta o'qiladi: bir vaqtda A B uchun, B A uchun yetkazsa ham
        // (taklif bonusi ikkala hisobni o'zgartiradi) tranzaksiyalar bir-birini kutib qolmaydi, bir haydovchining ikki
        // yetkazishi bir vaqtda tugasa ham hisoblagich yo'qolmaydi
        User senderRow = o.getSender();
        for (User party : me.getId() < senderRow.getId() ? List.of(me, senderRow) : List.of(senderRow, me)) locks.lockFresh(party);
        o.setStatus(OrderStatus.DELIVERED);
        o.setDeliveredAt(Instant.now());
        me.setDeliveriesCompleted(me.getDeliveriesCompleted() + 1);
        event(o, OrderStatus.DELIVERED, "Yuk qabul qiluvchiga topshirildi", me);
        growth.onDelivered(o);   // taklif havolasi orqali kelganlarga birinchi yetkazishda bonus
        notifications.notify(o.getSender(), "🎉 Yuk yetkazildi!",
                "\"" + o.getTitle() + "\" qabul qiluvchiga topshirildi. Iltimos, haydovchini baholang.",
                "#/orders/" + o.getId());
        notifications.notify(me, "Buyurtma yakunlandi",
                "\"" + o.getTitle() + "\" — rahmat! Yuboruvchini baholashni unutmang.", "#/orders/" + o.getId());
        return toDto(o, me);
    }

    @Transactional
    public OrderDto cancel(Long orderId, CancelRequest req) {
        User me = currentUser.get();
        Order o = findForUpdate(orderId);
        String reason = req == null ? null : trimToNull(req.reason());
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
                notifications.notify(assigned, "Buyurtma bekor qilindi",
                        "Yuboruvchi \"" + o.getTitle() + "\" ni bekor qildi" + (reason != null ? ": " + reason : ""),
                        "#/orders/" + o.getId());
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
            notifications.notify(o.getSender(), "Haydovchi voz kechdi",
                    me.getFullName() + " \"" + o.getTitle() + "\" ni olib keta olmasligini bildirdi. Buyurtmangiz qayta e'lon qilindi.",
                    "#/orders/" + o.getId());
            notifyDriversAboutNewOrder(o, o.getSender());
        } else if (me.getRole().isStaff()) {
            adminCancel(o, reason, me);
        } else {
            throw ApiException.forbidden("Ruxsat yo'q");
        }
        return toDto(o, me);
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
        String text = "\"" + o.getTitle() + "\" admin tomonidan bekor qilindi" + (reason != null ? ": " + reason : "");
        notifications.notify(o.getSender(), "Buyurtma bekor qilindi", text, "#/orders/" + o.getId());
        if (driver != null) {
            notifications.notify(driver, "Buyurtma bekor qilindi", text, "#/orders/" + o.getId());
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
        notifications.notify(driver, "Komissiya qaytarildi",
                money(fee) + " balansingizga qaytarildi: \"" + o.getTitle() + "\"", "#/profile");
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

    @Transactional(readOnly = true)
    public List<MessageDto> messages(Long orderId, Long afterId) {
        User me = currentUser.get();
        Order o = find(orderId);
        if (!isParticipant(o, me) && !me.getRole().isStaff()) throw ApiException.forbidden("Ruxsat yo'q");
        return messageRepository.findByOrderIdAndIdGreaterThanOrderByIdAsc(orderId, afterId == null ? 0 : afterId)
                .stream().map(MessageDto::of).toList();
    }

    @Transactional
    public MessageDto sendMessage(Long orderId, MessageRequest req) {
        User me = currentUser.get();
        Order o = find(orderId);
        if (!isParticipant(o, me)) throw ApiException.forbidden("Yozishma faqat yuboruvchi va haydovchi o'rtasida");
        if (o.getDriver() == null) throw ApiException.badRequest("Haydovchi tanlangandan keyin yozishish mumkin");
        OrderMessage m = new OrderMessage();
        m.setOrderId(orderId);
        m.setAuthor(me);
        m.setText(req.text().trim());
        messageRepository.save(m);
        User other = o.getSender().getId().equals(me.getId()) ? o.getDriver() : o.getSender();
        if (other.getTelegramChatId() != null) {
            notifications.telegramOnly(other, "💬 <b>" + TelegramClient.esc(me.getFullName()) + "</b> (#" + orderId + "):\n"
                    + TelegramClient.esc(m.getText()), "#/orders/" + orderId);
        }
        return MessageDto.of(m);
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
            notifications.notify(o.getSender(), "E'loningiz yopildi",
                    "\"" + o.getTitle() + "\" — olib ketish sanasi o'tgani uchun e'lon avtomatik yopildi. "
                            + "Yuk hali jo'natilmagan bo'lsa, yangi sana bilan qayta joylang.",
                    "#/orders/" + o.getId());
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

    private void notifyDriversAboutNewOrder(Order o, User sender) {
        try {
            List<User> drivers = userRepository.findDriversToNotify(o.getFromRegion().getId(), o.getToRegion().getId(), sender.getId());
            String html = (o.isUrgent() ? "🔥 <b>SHOSHILINCH</b>\n" : "") + "📦 <b>Yangi buyurtma</b>: " + TelegramClient.esc(o.getTitle()) + "\n"
                    + "📍 " + TelegramClient.esc(placeName(o.getFromRegion(), o.getFromDistrict())) + " → "
                    + TelegramClient.esc(placeName(o.getToRegion(), o.getToDistrict())) + "\n"
                    + (o.getWeightKg() != null ? "⚖️ " + fmt(o.getWeightKg()) + " kg\n" : "")
                    + (o.getDistanceKm() != null ? "🛣 " + Math.round(o.getDistanceKm()) + " km\n" : "")
                    + "💰 " + (o.getPrice() != null ? money(o.getPrice()) : "Narx kelishiladi");
            int n = 0;
            for (User d : drivers) {
                if (o.getRequiredBodyType() != null && d.getVehicle().getBodyType() != null
                        && d.getVehicle().getBodyType() != o.getRequiredBodyType()) continue;
                if (o.getWeightKg() != null && d.getVehicle().getCapacityKg() != null
                        && d.getVehicle().getCapacityKg() < o.getWeightKg()) continue;
                notifications.telegramOnly(d, html, "#/orders/" + o.getId());
                if (++n >= 300) break;
            }
        } catch (Exception e) {
            log.warn("Haydovchilarga xabar yuborishda xato: {}", TelegramClient.safe(e));
        }
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
        boolean isSender = viewer != null && o.getSender().getId().equals(viewer.getId());
        boolean isDriver = viewer != null && o.getDriver() != null && o.getDriver().getId().equals(viewer.getId());
        boolean isStaff = viewer != null && viewer.getRole().isStaff();
        String viewerRole = isSender ? "SENDER" : isDriver ? "DRIVER" : isStaff ? "STAFF" : "GUEST";
        // Telefonlar faqat buyurtma tomonlariga: yuboruvchi raqami haydovchiga buyurtma unga berilgandagina ochiladi
        // (aks holda platformani chetlab, komissiyasiz kelishib olish mumkin bo'lardi)
        boolean seeContacts = isSender || isDriver || isStaff;
        boolean seeExactPlace = seeContacts || o.getStatus() == OrderStatus.NEW;

        OfferDto myOffer = null;
        if (viewer != null && !isSender && viewer.isDriver() && o.getStatus() == OrderStatus.NEW) {
            myOffer = offerRepository.findFirstByOrderIdAndDriverIdAndStatus(o.getId(), viewer.getId(), OfferStatus.PENDING)
                    .map(OfferDto::of).orElse(null);
        }
        Boolean reviewed = null;
        if (o.getStatus() == OrderStatus.DELIVERED && (isSender || isDriver)) {
            reviewed = reviewRepository.existsByOrderIdAndAuthorId(o.getId(), viewer.getId());
        }
        return new OrderDto(
                o.getId(), o.getTitle(), o.getCargoType(), o.getWeightKg(), o.getVolumeM3(), o.getDescription(),
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
                isDriver || isStaff ? o.getCommission() : null);
    }

    private static PlaceDto place(Region r, District d, String address, Double lat, Double lng) {
        return new PlaceDto(r.getId(), r.getName(), d == null ? null : d.getId(), d == null ? null : d.getName(),
                address, lat, lng);
    }

    private static String placeName(Region r, District d) {
        return d == null ? r.getName() : r.getName() + ", " + d.getName();
    }

    static String money(Long v) {
        if (v == null) return "kelishiladi";
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.FRANCE);
        return nf.format(v).replace(' ', ' ').replace(' ', ' ') + " so'm";
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static long roundTo(long v, long step) {
        return Math.max(step, Math.round((double) v / step) * step);
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
