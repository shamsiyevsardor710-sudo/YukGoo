package uz.yukgo.user;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uz.yukgo.auth.VerificationService;
import uz.yukgo.common.PageResponse;
import uz.yukgo.user.UserDtos.*;

@RestController
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

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

    @PutMapping("/api/users/me/password")
    public void password(@Valid @RequestBody ChangePasswordRequest req) {
        userService.changePassword(req);
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
