package uz.yukgo.auth;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import uz.yukgo.auth.AuthDtos.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final VerificationService verificationService;
    private final TelegramAuthService telegramAuthService;

    @Operation(summary = "Telegram Mini App ichida avtomatik kirish (initData imzosi bot tokeni bilan tekshiriladi)")
    @PostMapping("/telegram")
    public AuthResponse telegram(@Valid @RequestBody TelegramInitRequest req) {
        return telegramAuthService.login(req.initData());
    }

    @Operation(summary = "1-qadam: telefon raqam kiritish — kod Telegram bot orqali keladi")
    @PostMapping("/register/start")
    public VerificationService.StartResult registerStart(@Valid @RequestBody PhoneRequest req) {
        return authService.registerStart(req);
    }

    @Operation(summary = "2-qadam: Telegramdan kelgan kodni tasdiqlash")
    @PostMapping("/verify")
    public Map<String, Object> verify(@Valid @RequestBody VerifyRequest req) {
        verificationService.verify(req.requestId(), req.code());
        return Map.of("verified", true);
    }

    @Operation(summary = "3-qadam: ism, familiya, parol va to'liq manzil")
    @PostMapping("/register/complete")
    public AuthResponse registerComplete(@Valid @RequestBody RegisterCompleteRequest req) {
        return authService.registerComplete(req);
    }

    @Operation(summary = "Kodni qayta yuborish")
    @PostMapping("/resend")
    public VerificationService.StartResult resend(@Valid @RequestBody RequestIdBody req) {
        return verificationService.resend(req.requestId());
    }

    @Operation(summary = "Tasdiqlash so'rovi holati (bot orqali kod yuborilganini bilish uchun)")
    @GetMapping("/verification/{requestId}")
    public VerificationService.StartResult status(@PathVariable String requestId) {
        return verificationService.status(requestId);
    }

    @Operation(summary = "Kirish: ism + familiya + parol (yoki telefon + parol)")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/reset/start")
    public VerificationService.StartResult resetStart(@Valid @RequestBody PhoneRequest req) {
        return authService.resetStart(req);
    }

    @PostMapping("/reset/complete")
    public AuthResponse resetComplete(@Valid @RequestBody ResetPasswordRequest req) {
        return authService.resetComplete(req);
    }
}
