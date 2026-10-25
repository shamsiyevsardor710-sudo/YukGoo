package uz.yukgo.payment;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.yukgo.payment.PaymentDtos.Options;
import uz.yukgo.payment.PaymentDtos.PaymentDto;
import uz.yukgo.payment.PaymentDtos.TopupRequest;
import uz.yukgo.payment.PaymentDtos.TopupResponse;
import uz.yukgo.security.CurrentUser;

/** Foydalanuvchi: balansni to'ldirish usullari, yangi to'lov va uning holati */
@RestController
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService payments;
    private final CurrentUser currentUser;

    @GetMapping("/api/payments/options")
    public Options options() {
        return payments.options(currentUser.get());
    }

    @PostMapping("/api/payments/topup")
    public TopupResponse topup(@Valid @RequestBody TopupRequest req) {
        return payments.topup(currentUser.get(), req.provider(), req.amount());
    }

    /** Shu tizim orqali eng oxirgi to'lovim (Paynet oynasi to'lov tushganini shu bilan biladi); yo'q bo'lsa — bo'sh javob */
    @GetMapping("/api/payments/latest")
    public PaymentDto latest(@RequestParam PaymentProvider provider) {
        return payments.latest(currentUser.get(), provider);
    }

    @GetMapping("/api/payments/{id}")
    public PaymentDto status(@PathVariable Long id) {
        return payments.status(currentUser.get(), id);
    }

    /** To'lov sahifasidan qaytish (Click return_url, Payme "c"): ilovaning profil sahifasi, u yerda to'lov holati ko'rinadi */
    @GetMapping("/pay/return")
    public ResponseEntity<Void> back(@RequestParam(value = "p", required = false) String p) {
        String id = p != null && p.matches("\\d{1,18}") ? p : null;
        return ResponseEntity.status(302).header("Location", "/#/profile" + (id != null ? "?topup=" + id : "")).build();
    }
}
