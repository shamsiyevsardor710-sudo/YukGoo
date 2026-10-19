package uz.yukgo.trip;

import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.common.ApiException;
import uz.yukgo.common.PageResponse;
import uz.yukgo.location.LocationService;
import uz.yukgo.location.Region;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.user.User;
import uz.yukgo.user.UserDtos.UserBrief;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TripService {
    private final TripRepository tripRepository;
    private final LocationService locationService;
    private final CurrentUser currentUser;

    public record TripRequest(
            @NotNull(message = "Qayerdan — viloyatni tanlang") Long fromRegionId,
            Long fromDistrictId,
            @NotNull(message = "Qayerga — viloyatni tanlang") Long toRegionId,
            Long toDistrictId,
            @NotNull(message = "Jo'nash vaqtini kiriting") LocalDateTime departAt,
            @Min(1) @Max(100000) Integer availableKg,
            @DecimalMin("0") @DecimalMax("200") Double availableM3,
            @Size(max = 100) String priceNote,
            @Size(max = 500) String comment) {}

    public record TripDto(Long id, UserBrief driver, Long fromRegionId, String fromRegion, String fromDistrict,
                          Long toRegionId, String toRegion, String toDistrict, LocalDateTime departAt,
                          Integer availableKg, Double availableM3, String priceNote, String comment,
                          boolean active, Instant createdAt) {
        static TripDto of(Trip t) {
            return new TripDto(t.getId(), UserBrief.of(t.getDriver()), t.getFromRegion().getId(), t.getFromRegion().getName(),
                    t.getFromDistrict() == null ? null : t.getFromDistrict().getName(),
                    t.getToRegion().getId(), t.getToRegion().getName(),
                    t.getToDistrict() == null ? null : t.getToDistrict().getName(),
                    t.getDepartAt(), t.getAvailableKg(), t.getAvailableM3(), t.getPriceNote(), t.getComment(),
                    t.isActive(), t.getCreatedAt());
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<TripDto> search(Long fromRegion, Long toRegion, Integer minKg, int page) {
        return PageResponse.of(tripRepository.search(fromRegion, toRegion, minKg,
                LocalDateTime.now().minusHours(12), PageRequest.of(page, 20)), TripDto::of);
    }

    @Transactional(readOnly = true)
    public List<TripDto> mine() {
        return tripRepository.findByDriverIdOrderByDepartAtDesc(currentUser.id()).stream().map(TripDto::of).toList();
    }

    @Transactional
    public TripDto create(TripRequest req) {
        User me = currentUser.get();
        if (!me.isDriver()) throw ApiException.badRequest("Reys e'lon qilish uchun profilingizga mashina ma'lumotlarini kiriting");
        Trip t = new Trip();
        t.setDriver(me);
        apply(t, req);
        tripRepository.save(t);
        return TripDto.of(t);
    }

    @Transactional
    public TripDto update(Long id, TripRequest req) {
        Trip t = own(id);
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
        if (req.departAt().isBefore(LocalDateTime.now().minusHours(1))) {
            throw ApiException.badRequest("Jo'nash vaqti o'tib ketgan");
        }
        Region fr = locationService.region(req.fromRegionId());
        Region tr = locationService.region(req.toRegionId());
        t.setFromRegion(fr);
        t.setFromDistrict(req.fromDistrictId() == null ? null : locationService.district(req.fromDistrictId(), fr));
        t.setToRegion(tr);
        t.setToDistrict(req.toDistrictId() == null ? null : locationService.district(req.toDistrictId(), tr));
        t.setDepartAt(req.departAt());
        t.setAvailableKg(req.availableKg());
        t.setAvailableM3(req.availableM3());
        t.setPriceNote(req.priceNote() == null || req.priceNote().isBlank() ? null : req.priceNote().trim());
        t.setComment(req.comment() == null || req.comment().isBlank() ? null : req.comment().trim());
    }
}
