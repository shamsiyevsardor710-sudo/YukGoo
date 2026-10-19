package uz.yukgo.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import uz.yukgo.common.PageResponse;
import uz.yukgo.security.CurrentUser;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService service;
    private final CurrentUser currentUser;

    @GetMapping
    public PageResponse<NotificationService.NotificationDto> list(@RequestParam(defaultValue = "0") int page) {
        return service.list(currentUser.id(), page);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread() {
        return Map.of("count", service.unread(currentUser.id()));
    }

    @PostMapping("/{id}/read")
    public void read(@PathVariable Long id) {
        service.markRead(currentUser.id(), id);
    }

    @PostMapping("/read-all")
    public void readAll() {
        service.markAllRead(currentUser.id());
    }
}
