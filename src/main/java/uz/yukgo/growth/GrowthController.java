package uz.yukgo.growth;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import uz.yukgo.growth.GrowthDtos.*;

@RestController
@RequiredArgsConstructor
public class GrowthController {
    private final GrowthService growth;

    @Operation(summary = "Shaxsiy taklif havolasi va natija (nechta odam keldi, nechtasiga bonus berildi)")
    @GetMapping("/api/users/me/referral")
    public ReferralDto myReferral() {
        return growth.myReferral();
    }

    @Operation(summary = "Admin: manbalar (kanal, hamkor, dala xodimi havolalari) va takliflar hisoboti")
    @GetMapping("/api/admin/sources")
    public SourcesReport report(@RequestParam(defaultValue = "30") int days) {
        return growth.report(days);
    }

    @Operation(summary = "Admin: yangi havola kodi (masalan agent_ali, kanal_yukmarkazi)")
    @PostMapping("/api/admin/sources")
    public SourceStat create(@Valid @RequestBody CreateSourceRequest req) {
        return growth.createSource(req);
    }

    @DeleteMapping("/api/admin/sources/{code}")
    public void delete(@PathVariable String code) {
        growth.deleteSource(code);
    }
}
