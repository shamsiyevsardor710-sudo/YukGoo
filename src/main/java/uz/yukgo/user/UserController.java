package uz.yukgo.user;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uz.yukgo.auth.AuthDtos.AuthResponse;
import uz.yukgo.auth.TelegramAuthService;
import uz.yukgo.auth.VerificationService;
import uz.yukgo.common.PageResponse;
import uz.yukgo.user.UserDtos.*;

@RestController
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final TelegramAuthService telegramAuthService;

    /*
     * Telegramga ulash alohida endpoint emas: u faqat parol (yoki Telegram kodi) isbotlangan so'rovda —
     * /api/auth/login, /register/complete, /reset/complete ichida telegramInitData bilan bo'ladi.
     */
    @Operation(summary = "Hisobni Telegramdan uzish: avtomatik kirish va Telegram xabarlari o'chadi")
    @PostMapping("/api/users/me/telegram/unlink")
    public MeDto unlinkTelegram() {
        return telegramAuthService.unlink();
    }

    @Operation(summary = "Mini App ichida chiqish: avtomatik kirish o'chadi, Telegram xabarlari kelaveradi")
    @PostMapping("/api/users/me/telegram/logout")
    public void telegramLogout() {
        telegramAuthService.pauseAutoLogin();
    }

    @Operation(summary = "Hisobni o'chirish: shaxsiy ma'lumotlar o'chiriladi, buyurtmalar tarixi anonim qoladi")
    @PostMapping("/api/users/me/delete")
    public void deleteAccount(@Valid @RequestBody DeleteAccountRequest req) {
        userService.deleteAccount(req);
    }

    @GetMapping("/api/users/me")
    public MeDto me() {
        return userService.me();
    }

    @Operation(summary = "Profilni tahrirlash (ism, familiya, manzil, o'zi haqida)")
    @PutMapping("/api/users/me")
    public MeDto update(@Valid @RequestBody UpdateProfileRequest req) {
        return userService.updateProfile(req);
    }

    @PostMapping(value = "/api/users/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MeDto avatar(@RequestPart("file") MultipartFile file) {
        return userService.uploadAvatar(file);
    }

    @DeleteMapping("/api/users/me/avatar")
    public MeDto deleteAvatar() {
        return userService.deleteAvatar();
    }

    @Operation(summary = "Mashina ma'lumotlari (kiritilsa foydalanuvchi haydovchi bo'ladi)")
    @PutMapping("/api/users/me/vehicle")
    public MeDto vehicle(@Valid @RequestBody VehicleDto req) {
        return userService.updateVehicle(req);
    }

    @PostMapping(value = "/api/users/me/vehicle/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MeDto vehiclePhoto(@RequestPart("file") MultipartFile file) {
        return userService.uploadVehiclePhoto(file);
    }

    @DeleteMapping("/api/users/me/vehicle")
    public MeDto removeVehicle() {
        return userService.removeVehicle();
    }

    @PutMapping("/api/users/me/settings")
    public MeDto settings(@RequestBody SettingsRequest req) {
        return userService.updateSettings(req);
    }

    @Operation(summary = "Parolni o'zgartirish: boshqa qurilmalardagi kirishlar bekor bo'ladi, shu qurilma uchun yangi token")
    @PutMapping("/api/users/me/password")
    public AuthResponse password(@Valid @RequestBody ChangePasswordRequest req) {
        return userService.changePassword(req);
    }

    @Operation(summary = "Telefon raqamni o'zgartirish: yangi raqamga Telegram orqali kod")
    @PostMapping("/api/users/me/phone/start")
    public VerificationService.StartResult phoneStart(@Valid @RequestBody ChangePhoneStart req) {
        return userService.changePhoneStart(req);
    }

    @PostMapping("/api/users/me/phone/confirm")
    public MeDto phoneConfirm(@Valid @RequestBody ChangePhoneConfirm req) {
        return userService.changePhoneConfirm(req);
    }

    @Operation(summary = "Haydovchining joriy joylashuvi (navigatsiya rejimida yuboriladi)")
    @PostMapping("/api/users/me/location")
    public void location(@Valid @RequestBody LocationUpdate req) {
        userService.updateLocation(req);
    }

    @Operation(summary = "Ommaviy profil — hamma ko'ra oladi")
    @GetMapping("/api/users/{id:\\d+}")
    public PublicUserDto publicProfile(@PathVariable Long id) {
        return userService.publicProfile(id);
    }

    @Operation(summary = "Haydovchilarni qidirish (reyting bo'yicha)")
    @GetMapping("/api/drivers")
    public PageResponse<PublicUserDto> drivers(@RequestParam(required = false) Long regionId,
                                               @RequestParam(required = false) BodyType bodyType,
                                               @RequestParam(required = false) Integer minCapacity,
                                               @RequestParam(defaultValue = "0") int page) {
        return userService.drivers(regionId, bodyType, minCapacity, page);
    }
}
