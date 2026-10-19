package uz.yukgo.location;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.common.ApiException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LocationService {
    private final RegionRepository regionRepository;
    private final DistrictRepository districtRepository;

    public record RegionDto(Long id, String name, Double lat, Double lng) {
        public static RegionDto of(Region r) {
            return r == null ? null : new RegionDto(r.getId(), r.getName(), r.getLat(), r.getLng());
        }
    }

    public record DistrictDto(Long id, Long regionId, String name) {
        public static DistrictDto of(District d) {
            return d == null ? null : new DistrictDto(d.getId(), d.getRegion().getId(), d.getName());
        }
    }

    @Transactional(readOnly = true)
    public List<RegionDto> regions() {
        return regionRepository.findAllByOrderBySortOrderAsc().stream().map(RegionDto::of).toList();
    }

    @Transactional(readOnly = true)
    public List<DistrictDto> districts(Long regionId) {
        return districtRepository.findByRegionIdOrderByNameAsc(regionId).stream().map(DistrictDto::of).toList();
    }

    public Region region(Long id) {
        if (id == null) throw ApiException.badRequest("Viloyat tanlanmagan");
        return regionRepository.findById(id).orElseThrow(() -> ApiException.badRequest("Viloyat topilmadi"));
    }

    /** Tuman tanlangan viloyatga tegishli ekanini ham tekshiradi. */
    public District district(Long id, Region region) {
        if (id == null) throw ApiException.badRequest("Tuman tanlanmagan");
        District d = districtRepository.findById(id).orElseThrow(() -> ApiException.badRequest("Tuman topilmadi"));
        if (region != null && !d.getRegion().getId().equals(region.getId())) {
            throw ApiException.badRequest("Tuman tanlangan viloyatga tegishli emas");
        }
        return d;
    }
}
