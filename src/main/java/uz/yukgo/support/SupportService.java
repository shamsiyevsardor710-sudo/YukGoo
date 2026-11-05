package uz.yukgo.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.common.ApiException;
import uz.yukgo.common.PageResponse;
import uz.yukgo.notification.NotificationService;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;
import uz.yukgo.user.Vehicle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * «Biz bilan bog'lanish»: foydalanuvchi va haydovchilar taklif, muammo yoki savol yozadi — ega va faol adminlarga darhol
 * xabar boradi (🔔, Telegram, telefonga push). Ular «Haydovchilardan» / «Foydalanuvchilardan» bo'limlarida o'qiydi,
 * javob yozadi (muallifga bildirishnoma bo'lib boradi va «Mening murojaatlarim»da ko'rinadi) yoki yopadi.
 */
@Service
@RequiredArgsConstructor
public class SupportService {
    /** Bir foydalanuvchidan bir kunda ko'pi bilan shuncha murojaat (tasodifiy takror va spamga qarshi) */
    static final int MAX_PER_DAY = 5;
    static final int PAGE_SIZE = 30;
    /** Ega va adminlarga boradigan bildirishnomadagi matn uzunligi */
    static final int PREVIEW = 300;

    private final SupportMessageRepository repository;
    private final UserRepository userRepository;
    private final NotificationService notifications;
    private final CurrentUser currentUser;

    public record CreateRequest(
            @NotNull(message = "Murojaat turini tanlang") SupportMessage.Topic topic,
            @NotBlank(message = "Xabaringizni yozing")
            @Size(max = 1000, message = "Xabar 1000 belgidan oshmasin") String text) {}

    public record ReplyRequest(
            @NotBlank(message = "Javob matnini yozing")
            @Size(max = 1000, message = "Javob 1000 belgidan oshmasin") String text) {}

    /** Muallifning o'ziga ko'rinadigan murojaat (kim javob bergani ko'rsatilmaydi — «YukGo javobi») */
    public record MineDto(Long id, SupportMessage.Topic topic, String text, SupportMessage.Status status, String reply,
                          Instant repliedAt, Instant createdAt) {
        static MineDto of(SupportMessage m) {
            return new MineDto(m.getId(), m.getTopic(), m.getText(), m.getStatus(), m.getReply(), m.getRepliedAt(),
                    m.getCreatedAt());
        }
    }

    /** Ega va adminlar ko'radigan muallif: qo'ng'iroq qilish uchun telefon, haydovchi bo'lsa — mashinasi */
    public record AuthorDto(Long id, String fullName, String phone, String avatarUrl, String place, String vehicle,
                            boolean active) {
        static AuthorDto of(User u) {
            String place = u.getRegion() == null ? null
                    : (u.getDistrict() != null ? u.getDistrict().getName() + ", " : "") + u.getRegion().getName();
            Vehicle v = u.isDriver() ? u.getVehicle() : null;
            String vehicle = v == null ? null : (v.getBrand() + (v.getModel() != null && !v.getModel().isBlank() ? " " + v.getModel() : "")
                    + (v.getPlateNumber() != null ? " · " + v.getPlateNumber() : ""));
            return new AuthorDto(u.getId(), u.getFullName(), u.getPhone(), u.getAvatarUrl(), place, vehicle, u.isActive());
        }
    }

    public record StaffDto(Long id, SupportMessage.Topic topic, String text, SupportMessage.Status status, String reply,
                           String repliedByName, Instant repliedAt, Instant createdAt, boolean fromDriver, AuthorDto author) {
        static StaffDto of(SupportMessage m) {
            return new StaffDto(m.getId(), m.getTopic(), m.getText(), m.getStatus(), m.getReply(), m.getRepliedByName(),
                    m.getRepliedAt(), m.getCreatedAt(), m.isFromDriver(), AuthorDto.of(m.getAuthor()));
        }
    }

    /** Javobsiz (yangi) murojaatlar soni: haydovchilardan va foydalanuvchilardan */
    public record CountsDto(long drivers, long users) {}

    // =====================================================================
    // Foydalanuvchi
    // =====================================================================

    @Transactional
    public MineDto create(CreateRequest req) {
        User me = currentUser.get();
        String text = req.text() == null ? "" : req.text().trim();
        if (text.length() < 5) throw ApiException.badRequest("Xabaringizni batafsilroq yozing");
        Instant now = Instant.now();
        if (repository.countByAuthorIdAndCreatedAtAfter(me.getId(), now.minus(Duration.ofDays(1))) >= MAX_PER_DAY) {
            throw ApiException.badRequest("Bir kunda " + MAX_PER_DAY + " tadan ko'p murojaat yuborib bo'lmaydi. "
                    + "Javobni kuting yoki qo'ng'iroq qiling");
        }
        SupportMessage m = new SupportMessage();
        m.setAuthor(me);
        m.setFromDriver(me.isDriver());
        m.setTopic(req.topic());
        m.setText(text);
        m.setCreatedAt(now);
        repository.save(m);

        String title = "📩 " + topicLabel(m.getTopic()) + ": " + me.getFullName() + (m.isFromDriver() ? " (haydovchi)" : "");
        String link = m.staffLink();
        for (User staff : userRepository.findActiveStaff()) {
            if (!staff.getId().equals(me.getId())) notifications.notify(staff, title, cut(text, PREVIEW), link);
        }
        return MineDto.of(m);
    }

    @Transactional(readOnly = true)
    public List<MineDto> mine() {
        return repository.findTop20ByAuthorIdOrderByIdDesc(currentUser.id()).stream().map(MineDto::of).toList();
    }

    // =====================================================================
    // Ega va adminlar (/api/admin/** — faqat ularga ochiq)
    // =====================================================================

    /** status null — hammasi */
    @Transactional(readOnly = true)
    public PageResponse<StaffDto> list(boolean fromDrivers, SupportMessage.Status status, int page) {
        var p = PageRequest.of(Math.max(0, page), PAGE_SIZE);
        return PageResponse.of(status == null ? repository.findForStaff(fromDrivers, p)
                : repository.findForStaffByStatus(fromDrivers, status, p), StaffDto::of);
    }

    @Transactional(readOnly = true)
    public CountsDto counts() {
        return new CountsDto(repository.countByFromDriverAndStatus(true, SupportMessage.Status.NEW),
                repository.countByFromDriverAndStatus(false, SupportMessage.Status.NEW));
    }

    /** Javob yozish (yoki avvalgi javobni yangilash): muallifga bildirishnoma, Telegram va push boradi */
    @Transactional
    public StaffDto reply(Long id, ReplyRequest req) {
        User me = currentUser.get();
        String text = req.text() == null ? "" : req.text().trim();
        if (text.isEmpty()) throw ApiException.badRequest("Javob matnini yozing");
        SupportMessage m = find(id);
        m.setReply(text);
        m.setRepliedById(me.getId());
        m.setRepliedByName(me.getFullName());
        m.setRepliedAt(Instant.now());
        m.setStatus(SupportMessage.Status.ANSWERED);
        notifications.notify(m.getAuthor(), "📩 YukGo javobi: " + topicLabel(m.getTopic()).toLowerCase(), text, "#/support");
        return StaffDto.of(m);
    }

    /** Hal qilindi yoki javob kerak emas — «Yangi» ro'yxatidan chiqadi (muallifga xabar bormaydi) */
    @Transactional
    public StaffDto close(Long id) {
        SupportMessage m = find(id);
        m.setStatus(SupportMessage.Status.CLOSED);
        return StaffDto.of(m);
    }

    private SupportMessage find(Long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Murojaat topilmadi"));
    }

    static String topicLabel(SupportMessage.Topic t) {
        if (t == null) return "Murojaat";
        return switch (t) {
            case IDEA -> "Taklif";
            case PROBLEM -> "Muammo";
            case QUESTION -> "Savol";
            case OTHER -> "Murojaat";
        };
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
