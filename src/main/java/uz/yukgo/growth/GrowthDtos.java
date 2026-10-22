package uz.yukgo.growth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class GrowthDtos {
    private GrowthDtos() {}

    /** Foydalanuvchining shaxsiy taklif havolalari va natijasi */
    public record ReferralDto(String siteLink, String botLink, long invited, long rewarded, long bonus) {}

    /**
     * Bitta manba (havola kodi) bo'yicha natija.
     * activated — kamida bitta buyurtmasi yakunlanganlar (haydovchi yoki yuk egasi sifatida): agent va hamkorga shu bo'yicha to'lanadi.
     */
    public record SourceStat(String code, String name, String note, boolean saved, long users, long drivers,
                             long senders, long activated, Instant lastSignupAt, String siteLink, String botLink) {}

    /** Eng ko'p odam taklif qilganlar */
    public record ReferrerStat(Long userId, String name, String phone, long invited, long rewarded) {}

    public record SourcesReport(int days, long totalUsers, long attributedUsers, List<SourceStat> sources,
                                List<ReferrerStat> referrers) {}

    public record CreateSourceRequest(
            @NotBlank(message = "Kodni kiriting")
            @Pattern(regexp = "[a-z0-9_-]{2,40}", message = "Kod: 2-40 ta kichik lotin harfi, raqam, _ yoki -")
            String code,
            @NotBlank(message = "Nomini kiriting") @Size(max = 120) String name,
            @Size(max = 300) String note) {}
}
