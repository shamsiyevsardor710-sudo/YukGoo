package uz.yukgo.location;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/locations")
@RequiredArgsConstructor
public class LocationController {
    private final LocationService locationService;

    @GetMapping("/regions")
    public List<LocationService.RegionDto> regions() {
        return locationService.regions();
    }

    @GetMapping("/regions/{regionId}/districts")
    public List<LocationService.DistrictDto> districts(@PathVariable Long regionId) {
        return locationService.districts(regionId);
    }
}
