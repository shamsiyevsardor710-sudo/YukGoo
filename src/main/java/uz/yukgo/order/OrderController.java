package uz.yukgo.order;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uz.yukgo.common.PageResponse;
import uz.yukgo.order.OrderDtos.*;
import uz.yukgo.user.BodyType;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    @Operation(summary = "Ochiq buyurtmalar bozori (filtrlar bilan)")
    @GetMapping
    public PageResponse<OrderDto> market(@RequestParam(required = false) Long fromRegion,
                                         @RequestParam(required = false) Long toRegion,
                                         @RequestParam(required = false) CargoType cargoType,
                                         @RequestParam(required = false) DeliveryScope scope,
                                         @RequestParam(required = false) BodyType bodyType,
                                         @RequestParam(required = false) Double maxWeight,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "0") int page) {
        return orderService.market(fromRegion, toRegion, cargoType, scope, bodyType, maxWeight, q, page);
    }

    @Operation(summary = "Narxni taxminiy hisoblash (masofa va og'irlik bo'yicha)")
    @GetMapping("/estimate")
    public EstimateDto estimate(@RequestParam double distanceKm,
                                @RequestParam(required = false) Double weightKg,
                                @RequestParam(required = false) DeliveryScope scope) {
        return orderService.estimate(distanceKm, weightKg, scope);
    }

    @PostMapping
    public OrderDto create(@Valid @RequestBody CreateOrderRequest req) {
        return orderService.create(req);
    }

    @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public OrderDto photo(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return orderService.uploadPhoto(id, file);
    }

    @Operation(summary = "Mening buyurtmalarim. role: sender | driver | offers; group: active | done | all")
    @GetMapping("/my")
    public PageResponse<OrderDto> my(@RequestParam(defaultValue = "sender") String role,
                                     @RequestParam(defaultValue = "all") String group,
                                     @RequestParam(defaultValue = "0") int page) {
        return orderService.my(role, group, page);
    }

    @GetMapping("/{id}")
    public OrderDto get(@PathVariable Long id) {
        return orderService.get(id);
    }

    // ---- Takliflar ----

    @Operation(summary = "Haydovchi narx taklifi yuboradi (yoki mavjud taklifini yangilaydi)")
    @PostMapping("/{id}/offers")
    public OfferDto offer(@PathVariable Long id, @Valid @RequestBody OfferRequest req) {
        return orderService.makeOffer(id, req);
    }

    @GetMapping("/{id}/offers")
    public List<OfferDto> offers(@PathVariable Long id) {
        return orderService.offers(id);
    }

    @PostMapping("/{id}/offers/{offerId}/accept")
    public OrderDto accept(@PathVariable Long id, @PathVariable Long offerId) {
        return orderService.acceptOffer(id, offerId);
    }

    @PostMapping("/{id}/offers/{offerId}/reject")
    public void reject(@PathVariable Long id, @PathVariable Long offerId) {
        orderService.rejectOffer(id, offerId);
    }

    @PostMapping("/{id}/offers/{offerId}/withdraw")
    public void withdraw(@PathVariable Long id, @PathVariable Long offerId) {
        orderService.withdrawOffer(id, offerId);
    }

    @Operation(summary = "Haydovchi e'londagi narxga rozi bo'lib buyurtmani darhol oladi")
    @PostMapping("/{id}/take")
    public OrderDto take(@PathVariable Long id) {
        return orderService.take(id);
    }

    // ---- Holat ----

    @PostMapping("/{id}/pickup")
    public OrderDto pickup(@PathVariable Long id) {
        return orderService.pickUp(id);
    }

    @Operation(summary = "Topshirish — qabul qiluvchidagi 4 xonali kod bilan")
    @PostMapping("/{id}/deliver")
    public OrderDto deliver(@PathVariable Long id, @Valid @RequestBody DeliverRequest req) {
        return orderService.deliver(id, req);
    }

    @PostMapping("/{id}/cancel")
    public OrderDto cancel(@PathVariable Long id, @Valid @RequestBody(required = false) CancelRequest req) {
        return orderService.cancel(id, req);
    }

    // ---- Kuzatuv, tarix, yozishma ----

    @Operation(summary = "Haydovchi GPS joylashuvini yuboradi")
    @PostMapping("/{id}/track")
    public void track(@PathVariable Long id, @Valid @RequestBody TrackRequest req) {
        orderService.track(id, req);
    }

    @GetMapping("/{id}/track")
    public TrackDto trackInfo(@PathVariable Long id) {
        return orderService.trackInfo(id);
    }

    @GetMapping("/{id}/events")
    public List<EventDto> events(@PathVariable Long id) {
        return orderService.events(id);
    }

    @GetMapping("/{id}/messages")
    public List<MessageDto> messages(@PathVariable Long id, @RequestParam(required = false) Long after) {
        return orderService.messages(id, after);
    }

    @PostMapping("/{id}/messages")
    public MessageDto send(@PathVariable Long id, @Valid @RequestBody MessageRequest req) {
        return orderService.sendMessage(id, req);
    }
}
