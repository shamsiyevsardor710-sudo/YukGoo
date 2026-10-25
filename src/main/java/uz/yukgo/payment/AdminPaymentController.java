package uz.yukgo.payment;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.yukgo.common.PageResponse;
import uz.yukgo.payment.PaymentDtos.AdminPaymentDto;
import uz.yukgo.payment.PaymentDtos.AdminSetup;
import uz.yukgo.payment.PaymentDtos.TopupRequest;
import uz.yukgo.payment.PaymentDtos.TopupResponse;
import uz.yukgo.security.CurrentUser;

/** Admin: onlayn to'lovlar ro'yxati, ulanish holati va sinov to'lovi (/api/admin/** — faqat ADMIN va OWNER) */
@RestController
@RequestMapping("/api/admin/payments")
@RequiredArgsConstructor
public class AdminPaymentController {
    private final PaymentService payments;
    private final CurrentUser currentUser;

    @GetMapping
    public PageResponse<AdminPaymentDto> list(@RequestParam(defaultValue = "0") int page) {
        return payments.adminList(page);
    }

    @GetMapping("/setup")
    public AdminSetup setup() {
        return payments.adminSetup();
    }

    /** Sinov to'lovi: admin o'ziga Click yoki Payme to'lovi ochadi (daromad tizimi o'chiq bo'lsa ham) */
    @PostMapping("/test")
    public TopupResponse test(@Valid @RequestBody TopupRequest req) {
        return payments.testTopup(currentUser.get(), req.provider(), req.amount());
    }
}
