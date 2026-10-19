package uz.yukgo.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.common.ApiException;
import uz.yukgo.common.PageResponse;
import uz.yukgo.notification.NotificationService;
import uz.yukgo.order.*;
import uz.yukgo.order.OrderDtos.OrderDto;
import uz.yukgo.review.ReviewService;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.telegram.TelegramClient;
import uz.yukgo.user.*;
import uz.yukgo.user.UserDtos.VehicleDto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin va ega (owner) huquqlari:
 *  • OWNER — hamma narsa: admin tayinlash/olib tashlash, adminni bloklash va chiqarib yuborish, egalikni topshirish
 *  • ADMIN — oddiy foydalanuvchilarni bloklash/blokdan chiqarish/chiqarib yuborish, buyurtmalar va shikoyatlarni boshqarish
 *  • Admin egaga va boshqa adminlarga hech narsa qila olmaydi; egani hech kim bloklay olmaydi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final ReportRepository reportRepository;
    private final AdminLogRepository logRepository;
    private final OrderService orderService;
    private final ReviewService reviewService;
    private final NotificationService notifications;
    private final TelegramClient telegram;
    private final CurrentUser currentUser;

    public record ReasonRequest(@Size(max = 300) String reason) {}

    public record RoleRequest(Role role) {}

    public record BroadcastRequest(@NotBlank(message = "Xabar matnini kiriting") @Size(max = 3000) String text) {}

    public record ResolveRequest(Report.Status status, @Size(max = 500) String resolution) {}

    public record AdminUserDto(Long id, String fullName, String phone, String avatarUrl, Role role, UserStatus status,
                               String blockReason, boolean driver, VehicleDto vehicle, double rating, int ratingCount,
                               int ordersCreated, int deliveriesCompleted, int cancelledAsDriver, String region,
                               boolean telegramLinked, Instant createdAt, Instant lastLoginAt,
                               boolean canManage, boolean canChangeRole) {}

    public record ReportDto(Long id, Long authorId, String authorName, Long targetId, String targetName, Long orderId,
                            String text, Report.Status status, String resolution, Instant createdAt) {
        static ReportDto of(Report r) {
            return new ReportDto(r.getId(), r.getAuthor().getId(), r.getAuthor().getFullName(),
                    r.getTarget() == null ? null : r.getTarget().getId(),
                    r.getTarget() == null ? null : r.getTarget().getFullName(),
                    r.getOrderId(), r.getText(), r.getStatus(), r.getResolution(), r.getCreatedAt());
        }
    }

    public record LogDto(Long id, String actorName, String action, Long targetUserId, String targetName,
                         String details, Instant createdAt) {
        static LogDto of(AdminLog l) {
            return new LogDto(l.getId(), l.getActorName(), l.getAction(), l.getTargetUserId(), l.getTargetName(),
                    l.getDetails(), l.getCreatedAt());
        }
    }

    // ---------------- Statistika ----------------

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("usersTotal", userRepository.count());
        m.put("usersActive", userRepository.countByStatus(UserStatus.ACTIVE));
        m.put("usersBlocked", userRepository.countByStatus(UserStatus.BLOCKED));
        m.put("usersRemoved", userRepository.countByStatus(UserStatus.REMOVED));
        m.put("drivers", userRepository.countDrivers());
        m.put("admins", userRepository.countByRole(Role.ADMIN));
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (OrderStatus s : OrderStatus.values()) byStatus.put(s.name(), orderRepository.countByStatus(s));
        m.put("ordersByStatus", byStatus);
        Instant today = LocalDate.now(ZoneId.of("Asia/Tashkent")).atStartOfDay(ZoneId.of("Asia/Tashkent")).toInstant();
        m.put("ordersToday", orderRepository.countByCreatedAtAfter(today));
        m.put("deliveredValue", orderRepository.sumDeliveredValue());
        m.put("openReports", reportRepository.countByStatus(Report.Status.OPEN));
        return m;
    }

    // ---------------- Foydalanuvchilar ----------------

    @Transactional(readOnly = true)
    public PageResponse<AdminUserDto> users(String q, Role role, UserStatus status, int page) {
        User me = currentUser.get();
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return PageResponse.of(userRepository.search(like, role, status, PageRequest.of(page, 30)), u -> toDto(u, me));
    }

    @Transactional
    public AdminUserDto block(Long id, String reason) {
        User me = currentUser.get();
        User t = manageable(me, id);
        t.setStatus(UserStatus.BLOCKED);
        t.setBlockReason(blankToNull(reason));
        log(me, "BLOCK", t, reason);
        notifyTelegram(t, "⛔ Hisobingiz bloklandi" + (reason != null && !reason.isBlank() ? ".\nSabab: " + reason : "."));
        return toDto(t, me);
    }

    @Transactional
    public AdminUserDto unblock(Long id) {
        User me = currentUser.get();
        User t = manageable(me, id);
        t.setStatus(UserStatus.ACTIVE);
        t.setBlockReason(null);
        log(me, "UNBLOCK", t, null);
        notifyTelegram(t, "✅ Hisobingiz blokdan chiqarildi. Xush kelibsiz!");
        return toDto(t, me);
    }

    /** "Chiqarib yuborish" — hisob o'chiriladi, kira olmaydi, ochiq buyurtmalari bekor qilinadi. */
    @Transactional
    public AdminUserDto remove(Long id, String reason) {
        User me = currentUser.get();
        User t = manageable(me, id);
        t.setStatus(UserStatus.REMOVED);
        t.setBlockReason(blankToNull(reason));
        if (t.getRole() == Role.ADMIN) t.setRole(Role.USER);
        for (Order o : orderRepository.findActiveForUser(t.getId(), List.of(OrderStatus.NEW, OrderStatus.ACCEPTED))) {
            if (o.getSender().getId().equals(t.getId())) {
                orderService.adminCancel(o, "Foydalanuvchi tizimdan chiqarildi", me);
            }
        }
        log(me, "REMOVE", t, reason);
        notifyTelegram(t, "🚫 Hisobingiz YukGo tizimidan chiqarildi" + (reason != null && !reason.isBlank() ? ".\nSabab: " + reason : "."));
        return toDto(t, me);
    }

    @Transactional
    public AdminUserDto restore(Long id) {
        User me = currentUser.get();
        User t = manageable(me, id);
        t.setStatus(UserStatus.ACTIVE);
        t.setBlockReason(null);
        log(me, "RESTORE", t, null);
        return toDto(t, me);
    }

    /** Faqat ega: foydalanuvchini admin qilish yoki adminlikdan olish. */
    @Transactional
    public AdminUserDto setRole(Long id, Role role) {
        User me = currentUser.get();
        if (me.getRole() != Role.OWNER) throw ApiException.forbidden("Rollarni faqat ega o'zgartira oladi");
        if (role == null || role == Role.OWNER) {
            throw ApiException.badRequest("Egalikni topshirish uchun alohida amaldan foydalaning");
        }
        User t = manageable(me, id);
        if (t.getStatus() != UserStatus.ACTIVE) throw ApiException.badRequest("Faol bo'lmagan foydalanuvchiga rol berib bo'lmaydi");
        Role old = t.getRole();
        t.setRole(role);
        log(me, role == Role.ADMIN ? "MAKE_ADMIN" : "REVOKE_ADMIN", t, old + " → " + role);
        notifyTelegram(t, role == Role.ADMIN ? "🛡 Sizga YukGo admin huquqi berildi." : "Admin huquqingiz olib tashlandi.");
        return toDto(t, me);
    }

    /** Faqat ega: egalikni boshqa foydalanuvchiga topshirish (o'zi admin bo'lib qoladi). */
    @Transactional
    public AdminUserDto transferOwnership(Long id) {
        User me = currentUser.get();
        if (me.getRole() != Role.OWNER) throw ApiException.forbidden("Faqat ega");
        User t = userRepository.findById(id).orElseThrow(() -> ApiException.notFound("Foydalanuvchi topilmadi"));
        if (t.getId().equals(me.getId())) throw ApiException.badRequest("Siz allaqachon egasiz");
        if (t.getStatus() != UserStatus.ACTIVE) throw ApiException.badRequest("Foydalanuvchi faol emas");
        t.setRole(Role.OWNER);
        me.setRole(Role.ADMIN);
        log(me, "TRANSFER_OWNERSHIP", t, null);
        notifyTelegram(t, "👑 Sizga YukGo platformasi egaligi topshirildi.");
        return toDto(t, t);
    }

    // ---------------- Buyurtmalar ----------------

    @Transactional(readOnly = true)
    public PageResponse<OrderDto> orders(OrderStatus status, String q, int page) {
        User me = currentUser.get();
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return PageResponse.of(orderRepository.adminSearch(status, like, PageRequest.of(page, 30)),
                o -> orderService.toDtoForAdmin(o, me));
    }

    @Transactional
    public void cancelOrder(Long id, String reason) {
        User me = currentUser.get();
        Order o = orderService.find(id);
        orderService.adminCancel(o, blankToNull(reason), me);
        logRepository.save(newLog(me, "CANCEL_ORDER", null, "#" + id + (reason != null ? ": " + reason : "")));
    }

    // ---------------- Shikoyatlar ----------------

    @Transactional(readOnly = true)
    public PageResponse<ReportDto> reports(Report.Status status, int page) {
        var p = PageRequest.of(page, 30);
        return PageResponse.of(status == null ? reportRepository.findAllByOrderByCreatedAtDesc(p)
                : reportRepository.findByStatusOrderByCreatedAtDesc(status, p), ReportDto::of);
    }

    @Transactional
    public ReportDto resolve(Long id, ResolveRequest req) {
        User me = currentUser.get();
        Report r = reportRepository.findById(id).orElseThrow(() -> ApiException.notFound("Murojaat topilmadi"));
        r.setStatus(req.status() == null ? Report.Status.RESOLVED : req.status());
        r.setResolution(blankToNull(req.resolution()));
        r.setResolvedById(me.getId());
        r.setResolvedAt(Instant.now());
        notifications.notify(r.getAuthor(), "Murojaatingiz ko'rib chiqildi",
                (r.getStatus() == Report.Status.RESOLVED ? "Hal qilindi" : "Rad etildi")
                        + (r.getResolution() != null ? ": " + r.getResolution() : ""), null);
        return ReportDto.of(r);
    }

    @Transactional
    public void deleteReview(Long id) {
        User me = currentUser.get();
        reviewService.delete(id);
        logRepository.save(newLog(me, "DELETE_REVIEW", null, "review #" + id));
    }

    // ---------------- Ommaviy xabar ----------------

    @Transactional
    public Map<String, Object> broadcast(String text) {
        User me = currentUser.get();
        List<Long> chats = userRepository.findAllActiveChatIds();
        logRepository.save(newLog(me, "BROADCAST", null, text.length() > 200 ? text.substring(0, 200) + "…" : text));
        telegram.broadcast(chats, "📢 <b>YukGo</b>\n\n" + TelegramClient.esc(text));
        return Map.of("recipients", chats.size(), "telegramEnabled", telegram.enabled());
    }

    @Transactional(readOnly = true)
    public PageResponse<LogDto> logs(int page) {
        return PageResponse.of(logRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, 50)), LogDto::of);
    }

    // ---------------- Huquqlar ----------------

    /** actor target ustida amal bajara oladimi? */
    public static boolean canManage(User actor, User target) {
        if (actor.getId().equals(target.getId())) return false;
        if (target.getRole() == Role.OWNER) return false;
        if (actor.getRole() == Role.OWNER) return true;
        return actor.getRole() == Role.ADMIN && target.getRole() == Role.USER;
    }

    private User manageable(User actor, Long targetId) {
        User t = userRepository.findById(targetId).orElseThrow(() -> ApiException.notFound("Foydalanuvchi topilmadi"));
        if (actor.getId().equals(t.getId())) throw ApiException.badRequest("O'zingizga nisbatan bu amalni bajara olmaysiz");
        if (t.getRole() == Role.OWNER) throw ApiException.forbidden("Egaga nisbatan hech qanday amal bajarib bo'lmaydi");
        if (!canManage(actor, t)) throw ApiException.forbidden("Adminlarni faqat ega boshqara oladi");
        return t;
    }

    private AdminUserDto toDto(User u, User viewer) {
        return new AdminUserDto(u.getId(), u.getFullName(), u.getPhone(), u.getAvatarUrl(), u.getRole(), u.getStatus(),
                u.getBlockReason(), u.isDriver(), VehicleDto.of(u.getVehicle()), u.getRatingAvg(), u.getRatingCount(),
                u.getOrdersCreated(), u.getDeliveriesCompleted(), u.getCancelledAsDriver(),
                u.getRegion() == null ? null : u.getRegion().getName(), u.getTelegramChatId() != null,
                u.getCreatedAt(), u.getLastLoginAt(),
                canManage(viewer, u), viewer.getRole() == Role.OWNER && canManage(viewer, u));
    }

    private void log(User actor, String action, User target, String details) {
        logRepository.save(newLog(actor, action, target, details));
    }

    private AdminLog newLog(User actor, String action, User target, String details) {
        AdminLog l = new AdminLog();
        l.setActorId(actor.getId());
        l.setActorName(actor.getFullName() + " (" + actor.getRole() + ")");
        l.setAction(action);
        if (target != null) {
            l.setTargetUserId(target.getId());
            l.setTargetName(target.getFullName());
        }
        l.setDetails(blankToNull(details));
        return l;
    }

    private void notifyTelegram(User u, String text) {
        if (u.getTelegramChatId() != null) telegram.send(u.getTelegramChatId(), text);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
