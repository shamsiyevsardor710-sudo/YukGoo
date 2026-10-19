package uz.yukgo.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import uz.yukgo.common.ApiException;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.user.UserRepository;

import java.util.Map;

/** Foydalanuvchilar uchun: shikoyat yoki adminga murojaat yuborish. */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {
    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public record CreateReport(Long targetUserId, Long orderId,
                               @NotBlank(message = "Murojaat matnini yozing") @Size(min = 5, max = 1000) String text) {}

    @PostMapping
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody CreateReport req) {
        Report r = new Report();
        r.setAuthor(currentUser.get());
        if (req.targetUserId() != null) {
            if (req.targetUserId().equals(currentUser.id())) throw ApiException.badRequest("O'zingizga shikoyat qila olmaysiz");
            r.setTarget(userRepository.findById(req.targetUserId())
                    .orElseThrow(() -> ApiException.notFound("Foydalanuvchi topilmadi")));
        }
        r.setOrderId(req.orderId());
        r.setText(req.text().trim());
        reportRepository.save(r);
        return Map.of("id", r.getId(), "message", "Murojaatingiz adminlarga yuborildi");
    }
}
