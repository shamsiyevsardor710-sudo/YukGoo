package uz.yukgo.order;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.yukgo.order.OrderDtos.InboxItem;

import java.util.List;
import java.util.Map;

/** «Xabarlar»: barcha yozishmalarim va o'qilmaganlar soni */
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {
    private final InboxService inbox;

    @GetMapping
    public List<InboxItem> list() {
        return inbox.inbox();
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", inbox.unreadCount());
    }
}
