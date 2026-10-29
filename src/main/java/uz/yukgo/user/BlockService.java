package uz.yukgo.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.common.ApiException;
import uz.yukgo.order.Offer;
import uz.yukgo.order.OfferRepository;
import uz.yukgo.order.OfferStatus;
import uz.yukgo.order.Order;
import uz.yukgo.order.OrderRepository;
import uz.yukgo.order.OrderStatus;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.user.UserDtos.BlockedUserDto;

import java.time.Instant;
import java.util.*;

/**
 * Foydalanuvchini bloklash (Google Play: 1:1 yozishmasi bor ilovalarda majburiy). Blok ikki tomonga ta'sir qiladi —
 * kim bloklaganidan qat'i nazar, ular:
 * <ul>
 *   <li>buyurtma yozishmasida bir-biriga xabar yoza olmaydi;</li>
 *   <li>bir-birining e'loniga taklif bera, uni ola yoki taklifini qabul qila olmaydi (ochiq takliflar bloklanganda yopiladi);</li>
 *   <li>bozor, reyslar va haydovchilar ro'yxatida bir-birini ko'rmaydi, yangi e'lon haqida xabar bormaydi.</li>
 * </ul>
 * Allaqachon boshlangan buyurtma davom etadi (yuk yo'lda bo'lishi mumkin) — faqat yozishma yopiladi.
 * Baholash cheklanmaydi: aks holda yomon baho olmaslik uchun bloklab qo'yish mumkin bo'lardi.
 */
@Service
@RequiredArgsConstructor
public class BlockService {
    static final int MAX_BLOCKS = 500;
    /** Men uni bloklaganman */
    public static final String BY_ME = "ME";
    /** U meni bloklagan */
    public static final String BY_THEM = "THEM";

    private final UserBlockRepository blocks;
    private final UserRepository users;
    private final OrderRepository orders;
    private final OfferRepository offers;
    private final CurrentUser currentUser;

    /** {@link #BY_ME}, {@link #BY_THEM} yoki null (blok yo'q) */
    public String state(Long me, Long other) {
        if (me == null || other == null || me.equals(other)) return null;
        if (blocks.existsByBlockerIdAndBlockedId(me, other)) return BY_ME;
        if (blocks.existsByBlockerIdAndBlockedId(other, me)) return BY_THEM;
        return null;
    }

    /**
     * Blok bo'lsa — xato. O'zim bloklagan bo'lsam, shuni aytamiz (blokdan chiqarish mumkin); u meni bloklagan
     * bo'lsa — kim bloklaganini oshkor qilmaydigan umumiy matn.
     */
    public void requireNone(Long me, Long other, String neutralMessage) {
        String s = state(me, other);
        if (BY_ME.equals(s)) throw ApiException.forbidden("Siz bu foydalanuvchini bloklagansiz. Avval uni blokdan chiqaring");
        if (BY_THEM.equals(s)) throw ApiException.forbidden(neutralMessage);
    }

    /** Shu foydalanuvchi bilan qaysi tomondan bo'lmasin blok bo'lganlar (ro'yxat va xabarlardan chiqarish uchun) */
    public Set<Long> related(Long userId) {
        if (userId == null) return Set.of();
        Set<Long> s = new HashSet<>(blocks.blockedBy(userId));
        s.addAll(blocks.blockersOf(userId));
        return s;
    }

    /**
     * Foydalanuvchi qatori ataylab bloklanmaydi: «taklifni qabul qilish» buyurtma qatorini bloklab, haydovchi qatoriga
     * tashqi kalit tekshiruvi (FOR KEY SHARE) bilan murojaat qiladi — foydalanuvchi qatorini FOR UPDATE bilan ushlab,
     * keyin buyurtmani kutish o'zaro kutishga (deadlock) olib kelardi. Tugma ikki marta bosilsa, ikkinchi yozuv noyob
     * kalitga uriladi va {@link UserController} mavjud blokni qaytaradi.
     * Takroriy chaqiruv ham ochiq takliflarni qayta tekshiradi (bir vaqtda yuborilgan taklif qolib ketgan bo'lsa — yopiladi).
     */
    @Transactional
    public BlockedUserDto block(Long targetId) {
        Long meId = currentUser.id();
        if (meId.equals(targetId)) throw ApiException.badRequest("O'zingizni bloklay olmaysiz");
        User target = users.findById(targetId)
                .filter(u -> u.getStatus() != UserStatus.REMOVED)
                .orElseThrow(() -> ApiException.notFound("Foydalanuvchi topilmadi"));
        UserBlock b = blocks.findByBlockerIdAndBlockedId(meId, targetId).orElse(null);
        if (b == null) {
            if (blocks.countByBlockerId(meId) >= MAX_BLOCKS) {
                throw ApiException.badRequest("Ko'pi bilan " + MAX_BLOCKS + " ta foydalanuvchini bloklash mumkin. "
                        + "Avval keraksizlarini blokdan chiqaring");
            }
            b = new UserBlock();
            b.setBlockerId(meId);
            b.setBlockedId(targetId);
            blocks.saveAndFlush(b);   // takror bo'lsa — shu yerda noyob kalit xatosi, hech narsa o'zgarmaydi
        }
        closeOpenOffers(meId, targetId);
        return BlockedUserDto.of(target, b.getCreatedAt());
    }

    /** Tugma ikki marta bosilganda: birinchi so'rov yozgan blok */
    @Transactional(readOnly = true)
    public BlockedUserDto existing(Long targetId) {
        UserBlock b = blocks.findByBlockerIdAndBlockedId(currentUser.id(), targetId)
                .orElseThrow(() -> ApiException.conflict("Qayta urinib ko'ring"));
        User target = users.findById(targetId).orElseThrow(() -> ApiException.notFound("Foydalanuvchi topilmadi"));
        return BlockedUserDto.of(target, b.getCreatedAt());
    }

    @Transactional
    public void unblock(Long targetId) {
        blocks.findByBlockerIdAndBlockedId(currentUser.id(), targetId).ifPresent(blocks::delete);
    }

    /** Men bloklaganlar — yangilari birinchi */
    @Transactional(readOnly = true)
    public List<BlockedUserDto> mine() {
        List<UserBlock> list = blocks.findByBlockerIdOrderByCreatedAtDesc(currentUser.id());
        Map<Long, User> byId = new HashMap<>();
        users.findAllById(list.stream().map(UserBlock::getBlockedId).toList()).forEach(u -> byId.put(u.getId(), u));
        return list.stream()
                .filter(b -> byId.containsKey(b.getBlockedId()))
                .map(b -> BlockedUserDto.of(byId.get(b.getBlockedId()), b.getCreatedAt()))
                .toList();
    }

    /** Hisob o'chirilganda (o'sha tranzaksiya ichida) */
    public void deleteAllFor(Long userId) {
        blocks.deleteAllFor(userId);
    }

    /**
     * Ochiq takliflar yopiladi: bloklangan haydovchining mening e'lonlarimga takliflari rad etiladi, mening uning
     * e'lonlariga takliflarim qaytarib olinadi. E'lonlar id tartibida bloklanadi — bir vaqtdagi «taklifni qabul qilish»
     * bilan to'qnashmasin. Avval faqat id'lar o'qiladi, holat esa qator bloklangandan keyin — eskirgan bo'lmasin.
     */
    private void closeOpenOffers(Long blocker, Long blocked) {
        Instant now = Instant.now();
        for (Long orderId : new TreeSet<>(offers.findOpenOrderIdsBetween(blocker, blocked))) {
            Order o = orders.lockById(orderId).orElse(null);
            if (o == null || o.getStatus() != OrderStatus.NEW) continue;
            Long sender = o.getSender().getId();
            boolean changed = false;
            for (Offer f : offers.findByOrderIdAndStatus(orderId, OfferStatus.PENDING)) {
                Long driver = f.getDriver().getId();
                if (driver.equals(blocked) && sender.equals(blocker)) f.setStatus(OfferStatus.REJECTED);
                else if (driver.equals(blocker) && sender.equals(blocked)) f.setStatus(OfferStatus.WITHDRAWN);
                else continue;
                f.setUpdatedAt(now);
                changed = true;
            }
            if (changed) o.setOffersCount((int) offers.countByOrderIdAndStatus(orderId, OfferStatus.PENDING));
        }
    }
}
