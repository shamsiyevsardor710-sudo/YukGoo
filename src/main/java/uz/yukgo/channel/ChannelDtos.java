package uz.yukgo.channel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ChannelDtos {
    private ChannelDtos() {}

    public record FeedDto(Long id, Long chatId, String title, String username, String chatType, Long regionId,
                          String regionName, boolean active, String sourceCode, long postsCount, long signups,
                          Instant createdAt, String addedBy) {}

    public record ChannelsDto(List<FeedDto> feeds, boolean mainMiniApp, String botUsername, boolean botEnabled) {}

    /** chat: "@kanal_nomi", "https://t.me/kanal_nomi" yoki "-100..." ID */
    public record AddFeedRequest(@NotBlank(message = "Kanal manzilini kiriting") @Size(max = 120) String chat, Long regionId) {}

    public record UpdateFeedRequest(Boolean active, Long regionId, Boolean allRegions) {}

    public record SettingsRequest(boolean mainMiniApp) {}
}
