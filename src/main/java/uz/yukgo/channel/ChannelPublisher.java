package uz.yukgo.channel;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import uz.yukgo.config.AppSettingRepository;
import uz.yukgo.order.Order;
import uz.yukgo.order.OrderRepository;
import uz.yukgo.telegram.TelegramClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Kanallarga e'lon chiqarish va keyin yangilash. Hamma ish bitta fon oqimida navbat bilan bajariladi:
 * bir buyurtma uchun ikki o'zgarish ketma-ket kelsa ham post ikki marta chiqib qolmaydi va API so'rov qaytishini kutmaydi.
 * Telegramga so'rovlar tranzaksiyadan tashqarida yuboriladi: avval qisqa o'qish tranzaksiyasida reja tuziladi,
 * har bir yuborilgan post esa darhol o'z kichik tranzaksiyasida saqlanadi (keyingi xato uni yo'qotmaydi).
 */
@Slf4j
@Component
public class ChannelPublisher {
    /** BotFather'da "Main Mini App" yoqilgan bo'lsa: tugma ilovani bir bosishda ochadi (t.me/BOT?startapp=...) */
    public static final String MAIN_MINI_APP = "telegram.mainMiniApp";

    private final OrderRepository orders;
    private final ChannelFeedRepository feeds;
    private final ChannelPostRepository posts;
    private final AppSettingRepository settings;
    private final TelegramClient telegram;
    private final TransactionTemplate tx;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "channel-publisher");
        t.setDaemon(true);
        return t;
    });

    public ChannelPublisher(OrderRepository orders, ChannelFeedRepository feeds, ChannelPostRepository posts,
                            AppSettingRepository settings, TelegramClient telegram, TransactionTemplate tx) {
        this.orders = orders;
        this.feeds = feeds;
        this.posts = posts;
        this.settings = settings;
        this.telegram = telegram;
        this.tx = tx;
    }

    @PreDestroy
    public void stop() {
        worker.shutdown();
    }

    /** Navbatga qo'yadi va darhol qaytadi */
    public void sync(Long orderId) {
        if (!telegram.enabled()) return;
        worker.submit(() -> {
            try {
                syncNow(orderId);
            } catch (Exception e) {
                log.warn("Kanal postini yangilab bo'lmadi (buyurtma #{}): {}", orderId, e.getMessage());
            }
        });
    }

    /** Yangi kanal posti: qaysi chatga, qanday tugmalar bilan */
    record Target(Long feedId, long chatId, Map<String, Object> markup) {}

    /** Mavjud post: tahrirlanadi */
    record Existing(Long postId, long chatId, int messageId, Map<String, Object> markup) {}

    record Plan(String state, String html, List<Target> targets, List<Existing> existing) {}

    /** Yangi ochiq buyurtma — mos kanallarga post; post bor bo'lsa — holatiga qarab tahrirlanadi. */
    void syncNow(Long orderId) {
        Plan plan = tx.execute(st -> plan(orderId));
        if (plan == null) return;
        if (plan.existing().isEmpty()) {
            for (Target t : plan.targets()) {
                Integer msgId = telegram.sendToChat(t.chatId(), plan.html(), t.markup());
                if (msgId == null) continue;
                tx.executeWithoutResult(st -> {
                    ChannelPost p = new ChannelPost();
                    p.setOrderId(orderId);
                    p.setFeedId(t.feedId());
                    p.setChatId(t.chatId());
                    p.setMessageId(msgId);
                    p.setState(plan.state());
                    posts.save(p);
                    feeds.incrementPosts(t.feedId());   // kanal shu orada o'chirilgan bo'lsa — 0 qator, xato emas
                });
            }
            return;
        }
        for (Existing e : plan.existing()) {
            telegram.editInChat(e.chatId(), e.messageId(), plan.html(), e.markup());
            tx.executeWithoutResult(st -> posts.updateState(e.postId(), plan.state()));
        }
    }

    /** Buyurtma, kanallar va postlar o'qiladi, matn va tugmalar tayyorlanadi (lazy maydonlar shu tranzaksiyada) */
    Plan plan(Long orderId) {
        Order o = orders.findById(orderId).orElse(null);
        if (o == null) return null;
        String state = OrderCard.state(o);
        String html = OrderCard.channelPost(o, state);
        List<ChannelPost> existing = posts.findByOrderId(orderId);
        if (existing.isEmpty()) {
            if (!"OPEN".equals(state)) return null;
            List<Target> targets = feeds.findByActiveTrue().stream().filter(f -> matches(f, o))
                    .map(f -> new Target(f.getId(), f.getChatId(), markup(o, f, state))).toList();
            return new Plan(state, html, targets, List.of());
        }
        Map<Long, ChannelFeed> byId = feeds.findAllById(existing.stream().map(ChannelPost::getFeedId).distinct().toList()).stream()
                .collect(Collectors.toMap(ChannelFeed::getId, Function.identity()));
        List<Existing> list = existing.stream()
                .map(p -> new Existing(p.getId(), p.getChatId(), p.getMessageId(), markup(o, byId.get(p.getFeedId()), state)))
                .toList();
        return new Plan(state, html, List.of(), list);
    }

    /** Kanal filtri: viloyat tanlangan bo'lsa — shu viloyatdan yoki shu viloyatga yuklar */
    static boolean matches(ChannelFeed f, Order o) {
        Long r = f.getRegionId();
        return r == null || r.equals(o.getFromRegion().getId()) || r.equals(o.getToRegion().getId());
    }

    /** Ochiq e'londa: "Taklif berish" (buyurtmani ochadi) va "Yuk e'lon qilish"; yopilganda — faqat ikkinchisi */
    Map<String, Object> markup(Order o, ChannelFeed f, String state) {
        String src = f != null && f.getSourceCode() != null ? f.getSourceCode() : "kanal";
        String newParam = ("new_" + src).length() <= 64 ? "new_" + src : "new";
        Map<String, Object> post = button("➕ Yuk e'lon qilish", telegram.deepLink(newParam));
        if (!"OPEN".equals(state)) return Map.of("inline_keyboard", List.of(List.of(post)));
        return Map.of("inline_keyboard", List.of(
                List.of(button("📩 Taklif berish", orderLink(o.getId(), src))),
                List.of(post)));
    }

    /** Buyurtma havolasi: Mini App to'g'ridan-to'g'ri (startapp) yoki bot orqali (start) — ikkalasida ham manba kodi bilan */
    public String orderLink(Long orderId, String src) {
        String param = "o" + orderId + "_" + src;
        if (param.length() > 64) param = "o" + orderId;
        boolean direct = settings.findById(MAIN_MINI_APP).map(s -> "true".equals(s.getValue())).orElse(false);
        return direct ? "https://t.me/" + telegram.botUsername() + "?startapp=" + param : telegram.deepLink(param);
    }

    private static Map<String, Object> button(String text, String url) {
        Map<String, Object> b = new HashMap<>();
        b.put("text", text);
        b.put("url", url);
        return b;
    }
}
