package uz.yukgo.channel;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import uz.yukgo.channel.ChannelDtos.*;

@RestController
@RequestMapping("/api/admin/channels")
@RequiredArgsConstructor
public class ChannelController {
    private final ChannelService channels;

    @GetMapping
    public ChannelsDto list() {
        return channels.list();
    }

    @Operation(summary = "Kanal qo'shish: bot u yerda admin bo'lishi kerak; yangi e'lonlar shu kanalga o'zi chiqadi")
    @PostMapping
    public FeedDto add(@Valid @RequestBody AddFeedRequest req) {
        return channels.add(req);
    }

    @PutMapping("/{id}")
    public FeedDto update(@PathVariable Long id, @RequestBody UpdateFeedRequest req) {
        return channels.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        channels.delete(id);
    }

    @Operation(summary = "BotFather'da Main Mini App yoqilganmi (tugma ilovani bir bosishda ochadi)")
    @PutMapping("/settings")
    public ChannelsDto settings(@RequestBody SettingsRequest req) {
        return channels.saveSettings(req);
    }
}
