package uz.yukgo.order;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.channel.OrderCard;
import uz.yukgo.common.Phones;
import uz.yukgo.location.District;
import uz.yukgo.location.Region;
import uz.yukgo.order.OrderDtos.InboxItem;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.user.BlockService;
import uz.yukgo.user.User;
import uz.yukgo.user.UserDtos.UserBrief;
import uz.yukgo.user.UserRepository;

import java.util.*;

/**
 * «Xabarlar»: foydalanuvchi qatnashgan barcha yozishmalar bir joyda (e'lon egasi sifatida — har bir haydovchi bilan,
 * haydovchi sifatida — har bir e'lon egasi bilan) va o'qilmagan xabarlar soni (tepadagi qizil raqam). Ro'yxatda eng yangi
 * {@link #MAX_THREADS} ta yozishma va o'qilmagani bor hammasi — raqam doim shu yerdan tozalanadi.
 */
@Service
@RequiredArgsConstructor
public class InboxService {
    /** Ro'yxatda ko'pi bilan shuncha yozishma (eng yangilari) */
    static final int MAX_THREADS = 50;

    private final OrderMessageRepository messageRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final BlockService blocks;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public List<InboxItem> inbox() {
        Long me = currentUser.id();
        // Eng yangi MAX_THREADS ta yozishma + o'qilmagan xabari bor hammasi (tepadagi raqam doim shu ro'yxatdan tozalanadi)
        Map<String, Integer> unread = new HashMap<>();
        Map<String, Object[]> threads = new LinkedHashMap<>();
        for (Object[] r : threadRows(me)) threads.put(r[0] + ":" + r[1], r);
        for (Object[] r : unreadRows(me)) {
            String key = r[0] + ":" + r[1];
            unread.put(key, ((Number) r[2]).intValue());
            threads.merge(key, new Object[]{r[0], r[1], r[3]}, (old, x) -> ((Number) old[2]).longValue() >= ((Number) x[2]).longValue() ? old : x);
        }
        if (threads.isEmpty()) return List.of();
        List<Object[]> rows = new ArrayList<>(threads.values());
        rows.sort((a, b) -> Long.compare(((Number) b[2]).longValue(), ((Number) a[2]).longValue()));

        Set<Long> hidden = blocks.related(me);
        Map<Long, OrderMessage> last = new HashMap<>();
        messageRepository.findAllById(rows.stream().map(r -> ((Number) r[2]).longValue()).toList()).forEach(m -> last.put(m.getId(), m));
        Map<Long, Order> orders = new HashMap<>();
        orderRepository.findAllById(rows.stream().map(r -> (Long) r[0]).distinct().toList()).forEach(o -> orders.put(o.getId(), o));
        // E'lon egasi sifatida — suhbatdosh haydovchilar
        Set<Long> driverIds = new HashSet<>();
        for (Object[] r : rows) {
            Order o = orders.get((Long) r[0]);
            if (o != null && o.getSender().getId().equals(me)) driverIds.add((Long) r[1]);
        }
        Map<Long, User> drivers = new HashMap<>();
        if (!driverIds.isEmpty()) userRepository.findAllById(driverIds).forEach(u -> drivers.put(u.getId(), u));

        List<InboxItem> out = new ArrayList<>();
        for (Object[] r : rows) {
            Long orderId = (Long) r[0];
            Long driverId = (Long) r[1];
            Order o = orders.get(orderId);
            OrderMessage m = last.get(((Number) r[2]).longValue());
            if (o == null || m == null) continue;
            boolean iAmSender = o.getSender().getId().equals(me);
            User other = iAmSender ? drivers.get(driverId) : o.getSender();
            if (other == null || hidden.contains(other.getId())) continue;
            boolean assigned = o.getDriver() != null && o.getDriver().getId().equals(driverId);
            boolean open = assigned || (o.getDriver() == null && o.getStatus() == OrderStatus.NEW);
            String text = assigned ? m.getText() : Phones.maskInText(m.getText());
            out.add(new InboxItem(orderId, driverId, title(o, iAmSender), o.getKind(), place(o.getFromRegion(), o.getFromDistrict()),
                    place(o.getToRegion(), o.getToDistrict()), o.getStatus(), UserBrief.of(other, false),
                    text.length() > 120 ? text.substring(0, 119) + "…" : text, m.getCreatedAt(), m.getAuthor().getId().equals(me),
                    unread.getOrDefault(orderId + ":" + driverId, 0), assigned, open, iAmSender));
        }
        return out;
    }

    /** Tepadagi qizil raqam: bloklanganlar bilan yozishmalar hisobga olinmaydi */
    @Transactional(readOnly = true)
    public long unreadCount() {
        Long me = currentUser.id();
        List<Object[]> rows = unreadRows(me);
        if (rows.isEmpty()) return 0;
        Set<Long> hidden = blocks.related(me);
        if (hidden.isEmpty()) return rows.stream().mapToLong(r -> ((Number) r[2]).longValue()).sum();
        Map<Long, Order> orders = new HashMap<>();
        orderRepository.findAllById(rows.stream().map(r -> (Long) r[0]).distinct().toList()).forEach(o -> orders.put(o.getId(), o));
        long total = 0;
        for (Object[] r : rows) {
            Order o = orders.get((Long) r[0]);
            if (o == null) continue;
            Long other = o.getSender().getId().equals(me) ? (Long) r[1] : o.getSender().getId();
            if (!hidden.contains(other)) total += ((Number) r[2]).longValue();
        }
        return total;
    }

    /** Eng yangi yozishmalarim (haydovchi va e'lon egasi sifatida birga) — [orderId, driverId, oxirgi xabar ID] */
    private List<Object[]> threadRows(Long me) {
        List<Object[]> all = new ArrayList<>(messageRepository.threadsAsDriver(me, PageRequest.of(0, MAX_THREADS)));
        all.addAll(messageRepository.threadsAsSender(me, PageRequest.of(0, MAX_THREADS)));
        all.sort((a, b) -> Long.compare(((Number) b[2]).longValue(), ((Number) a[2]).longValue()));
        return all.size() > MAX_THREADS ? all.subList(0, MAX_THREADS) : all;
    }

    /** O'qilmaganlar — [orderId, driverId, soni, oxirgisining ID] */
    private List<Object[]> unreadRows(Long me) {
        List<Object[]> all = new ArrayList<>(messageRepository.unreadAsDriver(me));
        all.addAll(messageRepository.unreadAsSender(me));
        return all;
    }

    /** E'lon nomi: yo'lovchida — necha kishi; begonaga sarlavhadagi telefon va havolalarsiz */
    static String title(Order o, boolean iAmSender) {
        return switch (o.getKind()) {
            case PASSENGER -> (o.getSeats() == null ? 1 : o.getSeats()) + " kishi";
            case PARCEL -> o.getTitle() == null || o.getTitle().equalsIgnoreCase("Pochta") ? "Pochta"
                    : "Pochta: " + (iAmSender ? o.getTitle() : OrderCard.publicTitle(o.getTitle()));
            default -> iAmSender ? o.getTitle() : OrderCard.publicTitle(o.getTitle());
        };
    }

    private static String place(Region r, District d) {
        return d != null ? d.getName() : r.getName();
    }
}
