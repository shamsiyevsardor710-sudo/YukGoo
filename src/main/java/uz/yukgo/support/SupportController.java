package uz.yukgo.support;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import uz.yukgo.common.ApiException;
import uz.yukgo.common.PageResponse;
import uz.yukgo.support.SupportService.*;

import java.util.List;

/**
 * «Biz bilan bog'lanish». /api/support — kirgan har bir foydalanuvchi uchun; /api/admin/support — faqat ega va adminlar
 * (SecurityConfig: /api/admin/** → ADMIN, OWNER).
 */
@RestController
@RequiredArgsConstructor
public class SupportController {
    private final SupportService service;

    @GetMapping("/api/support")
    public List<MineDto> mine() {
        return service.mine();
    }

    @PostMapping("/api/support")
    public MineDto create(@Valid @RequestBody CreateRequest req) {
        return service.create(req);
    }

    /** from=drivers — «Haydovchilardan», from=users — «Foydalanuvchilardan»; status=NEW — faqat yangilari */
    @GetMapping("/api/admin/support")
    public PageResponse<StaffDto> list(@RequestParam(defaultValue = "drivers") String from,
                                       @RequestParam(required = false) SupportMessage.Status status,
                                       @RequestParam(defaultValue = "0") int page) {
        return service.list(fromDrivers(from), status, page);
    }

    @GetMapping("/api/admin/support/counts")
    public CountsDto counts() {
        return service.counts();
    }

    @PostMapping("/api/admin/support/{id}/reply")
    public StaffDto reply(@PathVariable Long id, @Valid @RequestBody ReplyRequest req) {
        return service.reply(id, req);
    }

    @PostMapping("/api/admin/support/{id}/close")
    public StaffDto close(@PathVariable Long id) {
        return service.close(id);
    }

    static boolean fromDrivers(String from) {
        if ("drivers".equals(from)) return true;
        if ("users".equals(from)) return false;
        throw ApiException.badRequest("from: drivers yoki users");
    }
}
