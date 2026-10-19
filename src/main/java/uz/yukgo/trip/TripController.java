package uz.yukgo.trip;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import uz.yukgo.common.PageResponse;
import uz.yukgo.trip.TripService.TripDto;
import uz.yukgo.trip.TripService.TripRequest;

import java.util.List;

@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripController {
    private final TripService tripService;

    @Operation(summary = "Haydovchilarning reys e'lonlari (bo'sh joy bor mashinalar)")
    @GetMapping
    public PageResponse<TripDto> search(@RequestParam(required = false) Long fromRegion,
                                        @RequestParam(required = false) Long toRegion,
                                        @RequestParam(required = false) Integer minKg,
                                        @RequestParam(defaultValue = "0") int page) {
        return tripService.search(fromRegion, toRegion, minKg, page);
    }

    @GetMapping("/my")
    public List<TripDto> mine() {
        return tripService.mine();
    }

    @PostMapping
    public TripDto create(@Valid @RequestBody TripRequest req) {
        return tripService.create(req);
    }

    @PutMapping("/{id}")
    public TripDto update(@PathVariable Long id, @Valid @RequestBody TripRequest req) {
        return tripService.update(id, req);
    }

    @PostMapping("/{id}/close")
    public TripDto close(@PathVariable Long id) {
        return tripService.setActive(id, false);
    }

    @PostMapping("/{id}/open")
    public TripDto open(@PathVariable Long id) {
        return tripService.setActive(id, true);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        tripService.delete(id);
    }
}
