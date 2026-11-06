package uz.yukgo.trip;

import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.common.ApiException;
import uz.yukgo.common.PageResponse;
import uz.yukgo.location.District;
import uz.yukgo.location.LocationService;
import uz.yukgo.location.Region;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.user.Terms;
import uz.yukgo.user.User;
import uz.yukgo.user.UserDtos.UserBrief;
import uz.yukgo.user.UserRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TripService {
    /** Jo'nash vaqti foydalanuvchi kiritgan Toshkent vaqti (server UTC'da ishlaydi) */
    static final ZoneId TZ = ZoneId.of("Asia/Tashkent");

    private final TripRepository tripRepository;
    private final LocationService locationService;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final MatchService matches;
    private final TripPlanService plans;

    /**
     * Reys e'loni: qayerdan, qayerga, qachon (yoki «har kuni»), ixtiyoriy bo'sh o'rinlar, narx va izoh.
     * availableKg / availableM3 — eski ilova versiyalari yuboradi, endi so'ralmaydi.
     */
    public record TripRequest(
            @NotNull(message = "Qayerdan — viloyatni tanlang") Long fromRegionId,
            Long fromDistrictId,
            @NotNull(message = "Qayerga — viloyatni tanlang") Long toRegionId,
            Long toDistrictId,
            @NotNull(message = "Jo'nash vaqtini kiriting") LocalDateTime departAt,
            Integer availableKg,
            Double availableM3,
            @Size(max = 100) String priceNote,
            @Size(max = 500) String comment,
            Boolean daily,
            @Min(value = 1, message = "Bo'sh o'rin kamida 1") @Max(value = 60, message = "Bo'sh o'rinlar juda ko'p") Integer seats) {}

    /**
     * matches — faqat yangi reys e'lon qilinganda: shu yo'nalishdagi mos ochiq e'lonlar soni (ularga xabar ketdi);
     * plan — faqat o'z reyslarimda: «Reysga yig'ish» xulosasi (nechta yo'lovchi va pochta bitta reysga sig'adi)
     */
    public record TripDto(Long id, UserBrief driver, Long fromRegionId, String fromRegion, Long fromDistrictId,
                          String fromDistrict, Long toRegionId, String toRegion, Long toDistrictId, String toDistrict,
                          LocalDateTime departAt, boolean daily, Integer seats, Integer availableKg, Double availableM3,
                          String priceNote, String comment, boolean active, Instant createdAt, Integer matches,
                          TripPlanService.PlanSummary plan) {
        static TripDto of(Trip t) {
            return of(t, null, null);
        }

        static TripDto of(Trip t, Integer matches, TripPlanService.PlanSummary plan) {
            return new TripDto(t.getId(), UserBrief.of(t.getDriver()), t.getFromRegion().getId(), t.getFromRegion().getName(),
                    t.getFromDistrict() == null ? null : t.getFromDistrict().getId(),
                    t.getFromDistrict() == null ? null : t.getFromDistrict().getName(),
                    t.getToRegion().getId(), t.getToRegion().getName(),
                    t.getToDistrict() == null ? null : t.getToDistrict().getId(),
                    t.getToDistrict() == null ? null : t.getToDistrict().getName(),
                    t.getDepartAt(), t.isDaily(), t.getSeats(), t.getAvailableKg(), t.getAvailableM3(),
                    t.getPriceNote(), t.getComment(), t.isActive(), t.getCreatedAt(), matches, plan);
        }
    }

    /**
     * Reyslar: kirgan foydalanuvchiga avval o'z tumanidan va viloyatidan jo'naydiganlar.
     * Blok bo'lgan haydovchilarning reyslari (qaysi tomon bloklaganidan qat'i nazar) ko'rinmaydi.
     */
    @Transactional(readOnly = true)
    public PageResponse<TripDto> search(Long fromRegion, Long fromDistrict, Long toRegion, Long toDistrict, int page) {
        Long viewerId = currentUser.idOrNull();
        User viewer = viewerId == null ? null : userRepository.findById(viewerId).orElse(null);
        Region home = viewer == null ? null : viewer.getRegion();
        District homeDistrict = viewer == null ? null : viewer.getDistrict();
        return PageResponse.of(tripRepository.search(fromRegion, fromDistrict, toRegion, toDistrict,
                LocalDateTime.now(TZ).minusHours(12), viewerId,
                home == null ? null : home.getId(), homeDistrict == null ? null : homeDistrict.getId(),
                home == null ? null : home.getLat(), home == null ? null : home.getLng(),
                PageRequest.of(Math.max(0, page), 20)), TripDto::of);
    }

    @Transactional(readOnly = true)
    public List<TripDto> mine() {
        User me = currentUser.get();
        List<Trip> list = tripRepository.findByDriverIdOrderByDepartAtDesc(me.getId());
        Map<Long, TripPlanService.PlanSummary> summary = plans.summaries(me, list);
        return list.stream().map(t -> TripDto.of(t, null, summary.get(t.getId()))).toList();
    }

    /** Yangi reys: shu yo'nalishda yo'lovchi/pochta/yuk kutayotganlarga «haydovchi bor» xabari boradi */
    @Transactional
    public TripDto create(TripRequest req) {
        User me = currentUser.get();
        Terms.require(me);
        if (!me.isDriver()) throw ApiException.badRequest("Reys e'lon qilish uchun profilingizga mashina ma'lumotlarini kiriting");
        Trip t = new Trip();
        t.setDriver(me);
        apply(t, req);
        tripRepository.save(t);
        int found = matches.onNewTrip(t);
        return TripDto.of(t, found, plans.summary(me, t));
    }

    /** Botdan e'lon qilingan reys; duplicate — xuddi shunday reys yaqinda e'lon qilingan (yangisi ochilmadi) */
    public record BotTrip(Trip trip, int matches, boolean duplicate) {}

    /** Bot: bir kunda ko'pi bilan shuncha reys (xato yoki spam xabarlardan himoya) */
    static final int BOT_DAILY_LIMIT = 20;
    /** Shu vaqt ichida xuddi shu yo'nalish, kun va soatga reys qayta yozilsa — yangisi ochilmaydi */
    static final Duration BOT_DUPLICATE_WINDOW = Duration.ofMinutes(30);

    /**
     * Telegram bot: haydovchi botga «Kosonsoy - Toshkent, 3 ta joy bor» deb yozdi — reys darhol saytga chiqadi.
     * Xatolar (ApiException) tranzaksiyani buzmaydi: bot ularni foydalanuvchiga matn qilib aytadi.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public BotTrip createAs(User me, TripRequest req) {
        Terms.require(me);
        if (!me.isDriver()) throw ApiException.badRequest("Reys e'lon qilish uchun profilingizga mashina ma'lumotlarini kiriting");
        Instant now = Instant.now();
        List<Trip> recent = tripRepository.findByDriverIdAndCreatedAtAfterOrderByIdDesc(me.getId(), now.minus(Duration.ofDays(1)));
        boolean daily = Boolean.TRUE.equals(req.daily());
        for (Trip t : recent) {
            if (t.isActive() && t.getCreatedAt().isAfter(now.minus(BOT_DUPLICATE_WINDOW))
                    && t.getFromRegion().getId().equals(req.fromRegionId()) && t.getToRegion().getId().equals(req.toRegionId())
                    && Objects.equals(t.getFromDistrict() == null ? null : t.getFromDistrict().getId(), req.fromDistrictId())
                    && Objects.equals(t.getToDistrict() == null ? null : t.getToDistrict().getId(), req.toDistrictId())
                    && t.isDaily() == daily && t.getDepartAt().toLocalTime().equals(req.departAt().toLocalTime())
                    && (daily || t.getDepartAt().toLocalDate().equals(req.departAt().toLocalDate()))) {
                return new BotTrip(t, 0, true);
            }
        }
        if (recent.size() >= BOT_DAILY_LIMIT) {
            throw ApiException.badRequest("Bugun juda ko'p reys e'lon qildingiz — eskilarini saytda ko'ring");
        }
        Trip t = new Trip();
        t.setDriver(me);
        apply(t, req);
        tripRepository.save(t);
        return new BotTrip(t, matches.onNewTrip(t), false);
    }

    /** Bot: «❌ O'chirish» tugmasi. false — reys allaqachon o'chirilgan */
    @Transactional(noRollbackFor = ApiException.class)
    public boolean deleteAs(User me, Long id) {
        Trip t = tripRepository.findById(id).orElse(null);
        if (t == null) return false;
        if (!t.getDriver().getId().equals(me.getId())) throw ApiException.forbidden("Bu sizning reysingiz emas");
        tripRepository.delete(t);
        return true;
    }

    @Transactional
    public TripDto update(Long id, TripRequest req) {
        Trip t = own(id);
        Terms.require(t.getDriver());
        apply(t, req);
        return TripDto.of(t);
    }

    @Transactional
    public TripDto setActive(Long id, boolean active) {
        Trip t = own(id);
        t.setActive(active);
        return TripDto.of(t);
    }

    @Transactional
    public void delete(Long id) {
        User me = currentUser.get();
        Trip t = tripRepository.findById(id).orElseThrow(() -> ApiException.notFound("Reys topilmadi"));
        if (!t.getDriver().getId().equals(me.getId()) && !me.getRole().isStaff()) throw ApiException.forbidden("Ruxsat yo'q");
        tripRepository.delete(t);
    }

    private Trip own(Long id) {
        Trip t = tripRepository.findById(id).orElseThrow(() -> ApiException.notFound("Reys topilmadi"));
        if (!t.getDriver().getId().equals(currentUser.id())) throw ApiException.forbidden("Bu sizning reysingiz emas");
        return t;
    }

    private void apply(Trip t, TripRequest req) {
        boolean daily = Boolean.TRUE.equals(req.daily());
        LocalDateTime now = LocalDateTime.now(TZ);
        if (!daily && req.departAt().isBefore(now.minusHours(1))) {
            throw ApiException.badRequest("Jo'nash vaqti o'tib ketgan");
        }
        if (req.departAt().isAfter(now.plusDays(90))) {
            throw ApiException.badRequest("Jo'nash sanasi juda uzoq — 3 oy ichidagi sanani tanlang");
        }
        Region fr = locationService.region(req.fromRegionId());
        Region tr = locationService.region(req.toRegionId());
        t.setFromRegion(fr);
        t.setFromDistrict(req.fromDistrictId() == null ? null : locationService.district(req.fromDistrictId(), fr));
        t.setToRegion(tr);
        t.setToDistrict(req.toDistrictId() == null ? null : locationService.district(req.toDistrictId(), tr));
        t.setDepartAt(req.departAt());
        t.setDaily(daily);
        // «Bo'sh o'rinlar» yangi yozildi — «Reysga yig'ish» shu paytdan keyin olingan yo'lovchilarni shu o'rinlardan ayiradi
        if (t.getId() == null || !java.util.Objects.equals(t.getSeats(), req.seats())) t.setSeatsAt(Instant.now());
        t.setSeats(req.seats());
        // Bo'sh joy (kg) va hajm endi so'ralmaydi — eskisi ham tozalanadi
        t.setAvailableKg(null);
        t.setAvailableM3(null);
        t.setPriceNote(req.priceNote() == null || req.priceNote().isBlank() ? null : req.priceNote().trim());
        t.setComment(req.comment() == null || req.comment().isBlank() ? null : req.comment().trim());
    }
}
