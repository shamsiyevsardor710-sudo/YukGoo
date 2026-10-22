package uz.yukgo.driver;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uz.yukgo.driver.DriverVerificationDtos.*;

@RestController
@RequiredArgsConstructor
public class DriverVerificationController {
    private final DriverVerificationService service;

    @Operation(summary = "Haydovchi: «Tekshirilgan haydovchi» arizasi holati")
    @GetMapping("/api/users/me/driver-verification")
    public MyStatus my() {
        return service.myStatus();
    }

    @Operation(summary = "Haydovchi: haydovchilik guvohnomasi va texnik pasport rasmini yuborish (JPG/PNG/WEBP, har biri 5 MB gacha)")
    @PostMapping(value = "/api/users/me/driver-verification", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MyStatus submit(@RequestPart("license") MultipartFile license, @RequestPart("techPassport") MultipartFile techPassport) {
        return service.submit(license, techPassport);
    }

    @Operation(summary = "Admin: ko'rib chiqilishi kerak bo'lgan va oxirgi ko'rib chiqilgan arizalar")
    @GetMapping("/api/admin/driver-verifications")
    public AdminList list() {
        return service.adminList();
    }

    @Operation(summary = "Admin: arizadagi rasm (kind: license yoki tech_passport). Keshlanmaydi")
    @GetMapping("/api/admin/driver-verifications/{id}/files/{kind}")
    public ResponseEntity<byte[]> file(@PathVariable Long id, @PathVariable String kind) {
        FileData f = service.file(id, kind);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline")
                .body(f.data());
    }

    @PostMapping("/api/admin/driver-verifications/{id}/approve")
    public AdminItem approve(@PathVariable Long id) {
        return service.approve(id);
    }

    @PostMapping("/api/admin/driver-verifications/{id}/reject")
    public AdminItem reject(@PathVariable Long id, @Valid @RequestBody(required = false) RejectRequest req) {
        return service.reject(id, req == null ? null : req.reason());
    }
}
