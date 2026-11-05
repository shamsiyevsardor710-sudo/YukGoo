package uz.yukgo.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.yukgo.common.ApiException;
import uz.yukgo.common.PageResponse;
import uz.yukgo.config.AppProperties;
import uz.yukgo.push.PushService;
import uz.yukgo.telegram.TelegramClient;
import uz.yukgo.user.User;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Sayt ichidagi bildirishnoma + Telegram xabari + Android ilovaga push. */
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository repository;
    private final TelegramClient telegram;
    private final AppProperties props;
    /** Android ilovaga push (Firebase sozlanmagan bo'lsa — hech narsa yuborilmaydi). Konstruktorda emas: sinovlarda kerak emas */
    @Autowired(required = false)
    private PushService pushService;

    public record NotificationDto(Long id, String title, String body, String link, boolean read, Instant createdAt) {
        static NotificationDto of(Notification n) {
            return new NotificationDto(n.getId(), n.getTitle(), n.getBody(), n.getLink(), n.isRead(), n.getCreatedAt());
        }
    }

    @Transactional
    public void notify(User user, String title, String body, String link) {
        if (user == null) return;
        Notification n = new Notification();
        n.setUserId(user.getId());
        n.setTitle(cut(title, 150));
        n.setBody(cut(body, 1000));
        n.setLink(link);
        repository.save(n);
        if (user.getTelegramChatId() != null) {
            String html = "<b>" + TelegramClient.esc(title) + "</b>\n" + TelegramClient.esc(body);
            if (link != null) {
                telegram.sendWithAppButton(user.getTelegramChatId(), html, "Ochish", appUrl(link), siteUrl(link));
            } else {
                telegram.send(user.getTelegramChatId(), html);
            }
        }
        push(user, title, body, link);
    }

    /** Sayt ichida 🔔 va telefonga push, Telegramsiz (Telegramga chaqiruvchining o'zi tugmali xabar yuboradi) */
    @Transactional
    public void inApp(User user, String title, String body, String link) {
        if (user == null) return;
        Notification n = new Notification();
        n.setUserId(user.getId());
        n.setTitle(cut(title, 150));
        n.setBody(cut(body, 1000));
        n.setLink(link);
        repository.save(n);
        push(user, title, body, link);
    }

    /**
     * Faqat telefonga push (Android ilova): saytda 🔔 ham, Telegram ham emas. Masalan, yozishmadagi yangi xabar — saytda u
     * «💬 Xabarlar» belgisida ko'rinadi. Tranzaksiya ichida chaqirilsa — saqlangandan keyin yuboriladi.
     */
    public void push(User user, String title, String body, String link) {
        if (pushService == null || !pushService.enabled() || user == null || user.getId() == null) return;
        Long userId = user.getId();
        afterCommit(() -> pushService.send(userId, title, body, link));
    }

    /**
     * Haydovchiga yangi e'lon haqida: Telegram ulangan bo'lsa — Telegramga (karta), bo'lmasa — telefoniga push
     * (sayt ichida 🔔 emas: bunday xabarlar ko'p bo'ladi). @return xabar yuborildimi
     */
    public boolean telegramOrPush(User user, String html, String pushTitle, String pushBody, String link) {
        if (user.getTelegramChatId() != null) {
            if (link != null) telegram.sendWithAppButton(user.getTelegramChatId(), html, "Ko'rish", appUrl(link), siteUrl(link));
            else telegram.send(user.getTelegramChatId(), html);
            return true;
        }
        if (pushService == null || !pushService.enabled()) return false;
        push(user, pushTitle, pushBody, link);
        return true;
    }

    /** Telegram HTML'idan oddiy matn (push uchun): teglar olib tashlanadi, maxsus belgilar qaytariladi */
    public static String plain(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&").replaceAll("\n{2,}", "\n").trim();
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    /** Ustun uzunligidan oshib ketmasligi uchun (masalan, 1000 belgili sharh + prefiks). */
    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public String siteUrl(String hashLink) {
        return baseUrl() + "/" + hashLink;
    }

    /**
     * Mini App uchun manzil: sahifa "?r=" parametrida beriladi, chunki Telegram o'z ma'lumotlarini (#tgWebAppData)
     * aynan "#" dan keyin qo'shadi. Masalan "#/orders/5" → https://sayt/?r=%2Forders%2F5
     */
    public String appUrl(String hashLink) {
        return appUrl(hashLink, null, null);
    }

    /**
     * Mini App manzili + kelgan manba va taklif (src, ref): ilova ularni birinchi ochilishda eslab qoladi
     * va ro'yxatdan o'tishda yuboradi. Qiymatlar oldindan tozalangan bo'lishi kerak (faqat [a-z0-9_-]).
     */
    public String appUrl(String hashLink, String src, String ref) {
        String route = hashLink == null ? "/" : hashLink.startsWith("#") ? hashLink.substring(1) : hashLink;
        if (!route.startsWith("/")) route = "/" + route;
        return baseUrl() + "/?r=" + URLEncoder.encode(route, StandardCharsets.UTF_8)
                + (src != null && !src.isBlank() ? "&src=" + URLEncoder.encode(src, StandardCharsets.UTF_8) : "")
                + (ref != null && !ref.isBlank() ? "&ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8) : "");
    }

    /** Oddiy brauzer uchun sayt havolasi + manba/taklif ("https://sayt/?src=ch3#/orders/5") */
    public String siteUrl(String hashLink, String src, String ref) {
        StringBuilder q = new StringBuilder();
        if (src != null && !src.isBlank()) q.append("src=").append(URLEncoder.encode(src, StandardCharsets.UTF_8));
        if (ref != null && !ref.isBlank()) q.append(q.isEmpty() ? "" : "&").append("ref=").append(URLEncoder.encode(ref, StandardCharsets.UTF_8));
        String h = hashLink == null ? "" : hashLink;
        return baseUrl() + "/" + (q.isEmpty() ? "" : "?" + q) + h;
    }

    private String baseUrl() {
        String url = props.publicUrl() == null ? "" : props.publicUrl().trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationDto> list(Long userId, int page) {
        return PageResponse.of(repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, 30)),
                NotificationDto::of);
    }

    @Transactional(readOnly = true)
    public long unread(Long userId) {
        return repository.countByUserIdAndReadFalse(userId);
    }

    @Transactional
    public void markRead(Long userId, Long id) {
        Notification n = repository.findById(id).orElseThrow(() -> ApiException.notFound("Topilmadi"));
        if (!n.getUserId().equals(userId)) throw ApiException.forbidden("Ruxsat yo'q");
        n.setRead(true);
    }

    @Transactional
    public void markAllRead(Long userId) {
        repository.markAllRead(userId);
    }

    /** 🔔 ro'yxatini tozalash: foydalanuvchining barcha bildirishnomalari o'chiriladi. @return nechtasi o'chdi */
    @Transactional
    public int deleteAll(Long userId) {
        return repository.deleteAllForUser(userId);
    }
}
