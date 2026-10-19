package uz.yukgo.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import uz.yukgo.admin.AdminService.*;
import uz.yukgo.common.PageResponse;
import uz.yukgo.order.OrderDtos.OrderDto;
import uz.yukgo.order.OrderStatus;
import uz.yukgo.user.Role;
import uz.yukgo.user.UserStatus;

import java.util.Map;

/** Faqat ADMIN va OWNER (SecurityConfig: /api/admin/**). Nozik huquqlar AdminService ichida tekshiriladi. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final AdminService admin;

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return admin.stats();
    }

    @GetMapping("/users")
    public PageResponse<AdminUserDto> users(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) Role role,
                                            @RequestParam(required = false) UserStatus status,
                                            @RequestParam(defaultValue = "0") int page) {
        return admin.users(q, role, status, page);
    }

    @PostMapping("/users/{id}/block")
    public AdminUserDto block(@PathVariable Long id, @Valid @RequestBody(required = false) ReasonRequest req) {
        return admin.block(id, req == null ? null : req.reason());
    }

    @PostMapping("/users/{id}/unblock")
    public AdminUserDto unblock(@PathVariable Long id) {
        return admin.unblock(id);
    }

    @PostMapping("/users/{id}/remove")
    public AdminUserDto remove(@PathVariable Long id, @Valid @RequestBody(required = false) ReasonRequest req) {
        return admin.remove(id, req == null ? null : req.reason());
    }

    @PostMapping("/users/{id}/restore")
    public AdminUserDto restore(@PathVariable Long id) {
        return admin.restore(id);
    }

    @PostMapping("/users/{id}/role")
    public AdminUserDto role(@PathVariable Long id, @RequestBody RoleRequest req) {
        return admin.setRole(id, req.role());
    }

    @PostMapping("/users/{id}/transfer-ownership")
    public AdminUserDto transfer(@PathVariable Long id) {
        return admin.transferOwnership(id);
    }

    @GetMapping("/orders")
    public PageResponse<OrderDto> orders(@RequestParam(required = false) OrderStatus status,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "0") int page) {
        return admin.orders(status, q, page);
    }

    @PostMapping("/orders/{id}/cancel")
    public void cancelOrder(@PathVariable Long id, @Valid @RequestBody(required = false) ReasonRequest req) {
        admin.cancelOrder(id, req == null ? null : req.reason());
    }

    @GetMapping("/reports")
    public PageResponse<ReportDto> reports(@RequestParam(required = false) Report.Status status,
                                           @RequestParam(defaultValue = "0") int page) {
        return admin.reports(status, page);
    }

    @PostMapping("/reports/{id}/resolve")
    public ReportDto resolve(@PathVariable Long id, @Valid @RequestBody ResolveRequest req) {
        return admin.resolve(id, req);
    }

    @DeleteMapping("/reviews/{id}")
    public void deleteReview(@PathVariable Long id) {
        admin.deleteReview(id);
    }

    @PostMapping("/broadcast")
    public Map<String, Object> broadcast(@Valid @RequestBody BroadcastRequest req) {
        return admin.broadcast(req.text());
    }

    @GetMapping("/logs")
    public PageResponse<LogDto> logs(@RequestParam(defaultValue = "0") int page) {
        return admin.logs(page);
    }
}
