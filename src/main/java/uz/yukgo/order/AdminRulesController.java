package uz.yukgo.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import uz.yukgo.admin.AdminLog;
import uz.yukgo.admin.AdminLogRepository;
import uz.yukgo.common.ApiException;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.user.Role;
import uz.yukgo.user.User;

/**
 * Boshqaruv → «Buyurtmalar» → «Qoidalar»: pochta chegarasi (bitta haydovchi bir vaqtda nechta pochta oladi).
 * /api/admin/** ga faqat admin va ega kiradi; o'zgartirishni faqat ega qiladi.
 */
@RestController
@RequestMapping("/api/admin/rules")
@RequiredArgsConstructor
public class AdminRulesController {
    private final OrderRules rules;
    private final CurrentUser currentUser;
    private final AdminLogRepository logs;

    public record RulesDto(int parcelCap, int defaultParcelCap, boolean canEdit) {}

    public record RulesRequest(@NotNull(message = "Pochta chegarasini kiriting")
                               @Min(value = 0, message = "Pochta chegarasi manfiy bo'lmasin")
                               @Max(value = OrderRules.MAX_PARCEL_CAP, message = "Pochta chegarasi juda katta") Integer parcelCap) {}

    @GetMapping
    public RulesDto get() {
        User me = currentUser.get();
        return new RulesDto(rules.parcelCap(), OrderRules.DEFAULT_PARCEL_CAP, me.getRole() == Role.OWNER);
    }

    @PutMapping
    @Transactional
    public RulesDto update(@jakarta.validation.Valid @RequestBody RulesRequest req) {
        User me = currentUser.get();
        if (me.getRole() != Role.OWNER) throw ApiException.forbidden("Qoidalarni faqat ega o'zgartira oladi");
        int before = rules.parcelCap();
        int cap = rules.setParcelCap(req.parcelCap());
        if (before != cap) {
            AdminLog l = new AdminLog();
            l.setActorId(me.getId());
            l.setActorName(me.getFullName() + " (" + me.getRole() + ")");
            l.setAction("RULES");
            l.setDetails("Pochta chegarasi: " + before + " → " + cap + (cap == 0 ? " (cheklovsiz)" : ""));
            logs.save(l);
        }
        return new RulesDto(cap, OrderRules.DEFAULT_PARCEL_CAP, true);
    }
}
