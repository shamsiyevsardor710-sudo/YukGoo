package uz.yukgo.channel;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.admin.AdminLog;
import uz.yukgo.admin.AdminLogRepository;
import uz.yukgo.channel.ChannelDtos.*;
import uz.yukgo.common.ApiException;
import uz.yukgo.config.AppSetting;
import uz.yukgo.config.AppSettingRepository;
import uz.yukgo.location.LocationService;
import uz.yukgo.location.Region;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.telegram.TelegramClient;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;

import java.time.Instant;
import java.util.List;

/** Admin: e'lonlar avtomatik chiqadigan kanallar ro'yxati. */
@Service
@RequiredArgsConstructor
public class ChannelService {
    private final ChannelFeedRepository feeds;
    private final AppSettingRepository settings;
    private final AdminLogRepository logs;
    private final UserRepository users;
    private final LocationService locations;
    private final TelegramClient telegram;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public ChannelsDto list() {
        requireStaff();
        List<FeedDto> list = feeds.findAllByOrderByCreatedAtAsc().stream().map(this::toDto).toList();
        boolean direct = settings.findById(ChannelPublisher.MAIN_MINI_APP).map(s -> "true".equals(s.getValue())).orElse(false);
        return new ChannelsDto(list, direct, telegram.botUsername(), telegram.enabled());
    }

    @Transactional
    public FeedDto add(AddFeedRequest req) {
        User me = requireStaff();
        if (!telegram.enabled()) throw ApiException.badRequest("Telegram bot ulanmagan (TELEGRAM_BOT_TOKEN)");
        String chat = normalizeChat(req.chat());
        JsonNode info = telegram.getChat(chat);
        if (info == null) {
            throw ApiException.badRequest("Kanal topilmadi. Manzilni tekshiring (@kanal_nomi) va avval @"
                    + telegram.botUsername() + " ni kanalga admin qilib qo'shing");
        }
        long chatId = info.path("id").asLong();
        String type = info.path("type").asText("");
        if (!type.equals("channel") && !type.equals("supergroup") && !type.equals("group")) {
            throw ApiException.badRequest("Bu kanal yoki guruh emas");
        }
        JsonNode member = telegram.getChatMember(chatId, telegram.botId());
        String status = member == null ? "" : member.path("status").asText("");
        if (type.equals("channel")) {
            if (!status.equals("administrator") || !member.path("can_post_messages").asBoolean(false)) {
                throw ApiException.badRequest("Botni kanalga admin qiling va \"Xabar joylash\" (Post messages) huquqini bering");
            }
        } else if (!status.equals("administrator") && !status.equals("member")) {
            throw ApiException.badRequest("Botni avval guruhga qo'shing (yaxshisi admin qilib)");
        }
        if (feeds.findByChatId(chatId).isPresent()) throw ApiException.conflict("Bu kanal allaqachon ro'yxatda");
        Region region = req.regionId() == null ? null : locations.region(req.regionId());

        ChannelFeed f = new ChannelFeed();
        f.setChatId(chatId);
        f.setTitle(cut(info.path("title").asText(chat), 200));
        f.setUsername(info.path("username").isMissingNode() || info.path("username").isNull() ? null : info.path("username").asText());
        f.setChatType(type);
        f.setRegionId(region == null ? null : region.getId());
        f.setAddedBy(me.getFullName());
        feeds.save(f);
        f.setSourceCode("ch" + f.getId());
        log(me, "CHANNEL_ADD", f.getTitle() + (f.getUsername() != null ? " (@" + f.getUsername() + ")" : "") + " → " + f.getSourceCode());
        return toDto(f);
    }

    @Transactional
    public FeedDto update(Long id, UpdateFeedRequest req) {
        User me = requireStaff();
        ChannelFeed f = feeds.findById(id).orElseThrow(() -> ApiException.notFound("Kanal topilmadi"));
        if (req.active() != null) f.setActive(req.active());
        if (Boolean.TRUE.equals(req.allRegions())) f.setRegionId(null);
        else if (req.regionId() != null) f.setRegionId(locations.region(req.regionId()).getId());
        log(me, "CHANNEL_UPDATE", f.getTitle() + ": " + (f.isActive() ? "yoqilgan" : "to'xtatilgan"));
        return toDto(f);
    }

    @Transactional
    public void delete(Long id) {
        User me = requireStaff();
        ChannelFeed f = feeds.findById(id).orElseThrow(() -> ApiException.notFound("Kanal topilmadi"));
        feeds.delete(f);
        log(me, "CHANNEL_DELETE", f.getTitle());
    }

    @Transactional
    public ChannelsDto saveSettings(SettingsRequest req) {
        User me = requireStaff();
        AppSetting s = settings.findById(ChannelPublisher.MAIN_MINI_APP).orElseGet(() -> new AppSetting(ChannelPublisher.MAIN_MINI_APP, "false"));
        s.setValue(String.valueOf(req.mainMiniApp()));
        s.setUpdatedAt(Instant.now());
        settings.save(s);
        log(me, "CHANNEL_SETTINGS", "Mini App to'g'ridan-to'g'ri: " + req.mainMiniApp());
        return list();
    }

    /** "@kanal", "kanal", "https://t.me/kanal" yoki "-100..." → getChat qabul qiladigan ko'rinish */
    static String normalizeChat(String raw) {
        String s = raw.trim();
        s = s.replaceFirst("^(https?://)?(www\\.)?(t|telegram)\\.me/", "");
        if (s.matches("-?\\d+")) return s;
        if (s.startsWith("@")) s = s.substring(1);
        String[] parts = s.split("[/?#]");   // "https://t.me//" kabi manzilda bo'sh massiv qaytadi
        s = parts.length == 0 ? "" : parts[0];
        if (!s.matches("[A-Za-z0-9_]{4,64}")) throw ApiException.badRequest("Kanal manzili noto'g'ri: @kanal_nomi ko'rinishida yozing");
        return "@" + s;
    }

    private FeedDto toDto(ChannelFeed f) {
        String regionName = null;
        if (f.getRegionId() != null) {
            try { regionName = locations.region(f.getRegionId()).getName(); } catch (ApiException ignored) { }
        }
        long signups = f.getSourceCode() == null ? 0 : users.countBySignupSource(f.getSourceCode());
        return new FeedDto(f.getId(), f.getChatId(), f.getTitle(), f.getUsername(), f.getChatType(), f.getRegionId(),
                regionName, f.isActive(), f.getSourceCode(), f.getPostsCount(), signups, f.getCreatedAt(), f.getAddedBy());
    }

    private User requireStaff() {
        User me = currentUser.get();
        if (!me.getRole().isStaff()) throw ApiException.forbidden("Faqat admin");
        return me;
    }

    private void log(User actor, String action, String details) {
        AdminLog l = new AdminLog();
        l.setActorId(actor.getId());
        l.setActorName(actor.getFullName() + " (" + actor.getRole() + ")");
        l.setAction(action);
        l.setDetails(cut(details, 500));
        logs.save(l);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
