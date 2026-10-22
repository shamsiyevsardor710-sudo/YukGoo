package uz.yukgo.driver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uz.yukgo.admin.AdminLog;
import uz.yukgo.admin.AdminLogRepository;
import uz.yukgo.common.ApiException;
import uz.yukgo.driver.DriverVerificationDtos.*;
import uz.yukgo.notification.NotificationService;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;
import uz.yukgo.user.Vehicle;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static uz.yukgo.driver.DriverVerification.*;

/**
 * "Tekshirilgan haydovchi" belgisi: haydovchi haydovchilik guvohnomasi va texnik pasport rasmini yuboradi,
 * admin ko'rib chiqadi. Qaror qilingan zahoti rasmlar bazadan o'chiriladi — faqat natija qoladi.
 * Davlat raqami o'zgarsa yoki mashina o'chirilsa — belgi olib tashlanadi (qayta tekshiruv kerak).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverVerificationService {
    /** Bitta rasm (ilova yuborishdan oldin kichraytiradi — odatda 300–800 KB) */
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private final DriverVerificationRepository repository;
    private final DriverVerificationFileRepository files;
    private final UserRepository users;
    private final CurrentUser currentUser;
    private final NotificationService notifications;
    private final AdminLogRepository adminLogs;

    // =====================================================================
    // Haydovchi
    // =====================================================================

    @Transactional(readOnly = true)
    public MyStatus myStatus() {
        User me = currentUser.get();
        return status(me, repository.findFirstByUserIdOrderByIdDesc(me.getId()).orElse(null));
    }

    @Transactional
    public MyStatus submit(MultipartFile license, MultipartFile techPassport) {
        User me = currentUser.get();
        if (!me.isDriver()) throw ApiException.badRequest("Avval profilda mashina ma'lumotlarini kiriting");
        if (me.isVerifiedDriver()) throw ApiException.badRequest("Siz allaqachon tekshiruvdan o'tgansiz");
        Upload a = read(license, "Haydovchilik guvohnomasi");
        Upload b = read(techPassport, "Texnik pasport");
        // Ko'rib chiqilayotgan eski ariza bo'lsa — yangisi bilan almashadi (eski rasmlar o'chiriladi)
        removeAll(repository.findByUserIdAndStatus(me.getId(), PENDING));

        DriverVerification v = new DriverVerification();
        v.setUserId(me.getId());
        v.setPlateNumber(me.getVehicle().getPlateNumber());
        v.setVehicleInfo(vehicleInfo(me.getVehicle()));
        repository.save(v);
        saveFile(v.getId(), DriverVerificationFile.LICENSE, a);
        saveFile(v.getId(), DriverVerificationFile.TECH_PASSPORT, b);
        log.info("Haydovchi tekshiruviga ariza: user={} ariza={}", me.getId(), v.getId());
        return status(me, v);
    }

    /** Davlat raqami o'zgardi yoki mashina o'chirildi: belgi olinadi, ko'rib chiqilmagan ariza (eski raqam bilan) o'chiriladi */
    @Transactional
    public void vehicleChanged(User u) {
        u.setDriverVerified(null);
        u.setDriverVerifiedAt(null);
        removeAll(repository.findByUserIdAndStatus(u.getId(), PENDING));
    }

    /** Hisob o'chirilganda: arizalar va rasmlar butunlay */
    @Transactional
    public void deleteAllFor(Long userId) {
        removeAll(repository.findByUserId(userId));
    }

    // =====================================================================
    // Admin
    // =====================================================================

    @Transactional(readOnly = true)
    public AdminList adminList() {
        requireStaff();
        List<DriverVerification> pending = repository.findByStatusOrderBySubmittedAtAsc(PENDING);
        List<DriverVerification> recent = repository.findTop30ByStatusNotOrderByDecidedAtDesc(PENDING);
        Set<Long> ids = new HashSet<>();
        pending.forEach(v -> ids.add(v.getUserId()));
        recent.forEach(v -> ids.add(v.getUserId()));
        Map<Long, User> byId = users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return new AdminList(pending.stream().map(v -> item(v, byId.get(v.getUserId()))).toList(),
                recent.stream().map(v -> item(v, byId.get(v.getUserId()))).toList());
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return repository.countByStatus(PENDING);
    }

    @Transactional(readOnly = true)
    public FileData file(Long id, String kind) {
        requireStaff();
        String k = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        DriverVerificationFile f = files.findFirstByVerificationIdAndKind(id, k)
                .orElseThrow(() -> ApiException.notFound("Rasm topilmadi — ariza ko'rib chiqilgan va rasmlar o'chirilgan"));
        return new FileData(f.getContentType(), f.getData());
    }

    @Transactional
    public AdminItem approve(Long id) {
        User admin = requireStaff();
        DriverVerification v = lockPending(id);
        User u = users.findById(v.getUserId()).orElseThrow(() -> ApiException.notFound("Foydalanuvchi topilmadi"));
        if (!u.isActive()) throw ApiException.badRequest("Foydalanuvchi faol emas (bloklangan yoki o'chirilgan) — arizani rad eting");
        if (!u.isDriver()) throw ApiException.badRequest("Haydovchi mashinasini profildan o'chirgan — arizani rad eting");
        if (!Objects.equals(u.getVehicle().getPlateNumber(), v.getPlateNumber())) {
            throw ApiException.badRequest("Ariza yuborilgandan keyin davlat raqami o'zgargan — arizani rad eting, haydovchi qaytadan yuboradi");
        }
        Instant now = Instant.now();
        u.setDriverVerified(true);
        u.setDriverVerifiedAt(now);
        decide(v, APPROVED, admin, null, now);
        notifications.notify(u, "✅ Siz tekshiruvdan o'tdingiz",
                "Hujjatlaringiz tasdiqlandi. Profilingiz va takliflaringizda «Tekshirilgan haydovchi» belgisi ko'rinadi — "
                        + "yuk egalari sizga ko'proq ishonadi. Hujjat rasmlari tizimdan o'chirildi.", "#/profile");
        adminLog(admin, "DRIVER_VERIFY", u, "Tasdiqlandi: " + v.getVehicleInfo() + " · " + v.getPlateNumber());
        return item(v, u);
    }

    @Transactional
    public AdminItem reject(Long id, String reason) {
        User admin = requireStaff();
        DriverVerification v = lockPending(id);
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        decide(v, REJECTED, admin, why, Instant.now());
        User u = users.findById(v.getUserId()).orElse(null);
        if (u != null) {
            notifications.notify(u, "Tekshiruvdan o'tmadingiz",
                    (why != null ? "Sabab: " + why + ". " : "") + "Rasmlar o'chirildi. Hujjatlarning aniq, to'liq va o'qiladigan "
                            + "rasmini qaytadan yuborishingiz mumkin: Profil → «Tekshirilgan haydovchi».", "#/profile");
            adminLog(admin, "DRIVER_REJECT", u, "Rad etildi" + (why != null ? ": " + why : ""));
        }
        return item(v, u);
    }

    // =====================================================================

    private record Upload(String contentType, byte[] data) {}

    private static Upload read(MultipartFile file, String what) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest(what + " rasmi tanlanmagan");
        if (file.getSize() > MAX_FILE_BYTES) throw ApiException.badRequest(what + " rasmi juda katta (5 MB gacha)");
        byte[] data;
        try (InputStream in = file.getInputStream()) {
            data = in.readNBytes((int) MAX_FILE_BYTES + 1);
        } catch (IOException e) {
            throw ApiException.badRequest(what + " rasmini o'qib bo'lmadi");
        }
        if (data.length > MAX_FILE_BYTES) throw ApiException.badRequest(what + " rasmi juda katta (5 MB gacha)");
        String type = imageType(data);
        if (type == null) throw ApiException.badRequest(what + ": faqat JPG, PNG yoki WEBP rasm");
        return new Upload(type, data);
    }

    /** Fayl ichidagi belgilar bo'yicha (kengaytma yoki brauzer aytgan turga ishonmaymiz) */
    static String imageType(byte[] h) {
        if (h == null || h.length < 12) return null;
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return "image/jpeg";
        if ((h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "image/png";
        if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private void saveFile(Long verificationId, String kind, Upload u) {
        DriverVerificationFile f = new DriverVerificationFile();
        f.setVerificationId(verificationId);
        f.setKind(kind);
        f.setContentType(u.contentType());
        f.setData(u.data());
        files.save(f);
    }

    private void removeAll(List<DriverVerification> list) {
        if (list.isEmpty()) return;
        files.deleteByVerificationIds(list.stream().map(DriverVerification::getId).toList());
        repository.deleteAll(list);
    }

    private DriverVerification lockPending(Long id) {
        DriverVerification v = repository.lockById(id).orElseThrow(() -> ApiException.notFound("Ariza topilmadi"));
        if (!PENDING.equals(v.getStatus())) throw ApiException.conflict("Bu ariza allaqachon ko'rib chiqilgan");
        return v;
    }

    private void decide(DriverVerification v, String status, User admin, String reason, Instant at) {
        v.setStatus(status);
        v.setDecidedAt(at);
        v.setDecidedBy(cut(admin.getFullName(), 160));
        v.setRejectReason(cut(reason, 300));
        files.deleteByVerificationIds(List.of(v.getId()));   // hujjat rasmlari endi kerak emas
    }

    private static MyStatus status(User me, DriverVerification v) {
        if (me.isVerifiedDriver()) {
            return new MyStatus(APPROVED, true, null, v != null ? v.getSubmittedAt() : null, me.getDriverVerifiedAt());
        }
        if (v == null) return new MyStatus("NONE", false, null, null, null);
        // Tasdiqlangan, lekin keyin raqam o'zgargan — qayta ariza kerak
        if (APPROVED.equals(v.getStatus())) return new MyStatus("NONE", false, null, null, null);
        return new MyStatus(v.getStatus(), false, v.getRejectReason(), v.getSubmittedAt(), v.getDecidedAt());
    }

    private AdminItem item(DriverVerification v, User u) {
        Vehicle car = u != null ? u.getVehicle() : null;
        return new AdminItem(v.getId(), v.getStatus(), v.getUserId(),
                u != null ? u.getFullName() : "—", u != null ? u.getPhone() : null, u != null ? u.getAvatarUrl() : null,
                v.getVehicleInfo(), v.getPlateNumber(), car != null ? car.getPhotoUrl() : null,
                u != null ? u.getRatingAvg() : 0, u != null ? u.getRatingCount() : 0, u != null ? u.getDeliveriesCompleted() : 0,
                u != null ? u.getCreatedAt() : null, v.getSubmittedAt(), v.getDecidedAt(), v.getDecidedBy(), v.getRejectReason(),
                PENDING.equals(v.getStatus()));
    }

    static String vehicleInfo(Vehicle car) {
        if (car == null) return null;
        StringBuilder sb = new StringBuilder(car.getBrand() == null ? "" : car.getBrand());
        if (car.getModel() != null) sb.append(' ').append(car.getModel());
        if (car.getColor() != null) sb.append(", ").append(car.getColor());
        if (car.getYear() != null) sb.append(", ").append(car.getYear());
        return cut(sb.toString().trim(), 160);
    }

    private User requireStaff() {
        User me = currentUser.get();
        if (!me.getRole().isStaff()) throw ApiException.forbidden("Faqat admin");
        return me;
    }

    private void adminLog(User actor, String action, User target, String details) {
        AdminLog l = new AdminLog();
        l.setActorId(actor.getId());
        l.setActorName(actor.getFullName() + " (" + actor.getRole() + ")");
        l.setAction(action);
        l.setTargetUserId(target.getId());
        l.setTargetName(target.getFullName());
        l.setDetails(cut(details, 500));
        adminLogs.save(l);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
