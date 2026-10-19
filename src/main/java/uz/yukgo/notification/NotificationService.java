package uz.yukgo.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.common.ApiException;
import uz.yukgo.common.PageResponse;
import uz.yukgo.config.AppProperties;
import uz.yukgo.telegram.TelegramClient;
import uz.yukgo.user.User;

import java.time.Instant;

/** Sayt ichidagi bildirishnoma + Telegram xabari. */
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository repository;
    private final TelegramClient telegram;
    private final AppProperties props;

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
                telegram.sendWithButton(user.getTelegramChatId(), html, "Ochish", siteUrl(link));
            } else {
                telegram.send(user.getTelegramChatId(), html);
            }
        }
    }

    /** Faqat Telegram (masalan, yangi buyurtmalar haqida haydovchilarga). */
    public void telegramOnly(User user, String html, String link) {
        if (user.getTelegramChatId() == null) return;
        if (link != null) telegram.sendWithButton(user.getTelegramChatId(), html, "Ko'rish", siteUrl(link));
        else telegram.send(user.getTelegramChatId(), html);
    }

    /** Ustun uzunligidan oshib ketmasligi uchun (masalan, 1000 belgili sharh + prefiks). */
    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public String siteUrl(String hashLink) {
        String base = props.publicUrl().endsWith("/") ? props.publicUrl().substring(0, props.publicUrl().length() - 1) : props.publicUrl();
        return base + "/" + hashLink;
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
}
