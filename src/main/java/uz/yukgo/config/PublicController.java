package uz.yukgo.config;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.yukgo.channel.ChannelFeedRepository;
import uz.yukgo.order.CargoType;
import uz.yukgo.order.OrderRepository;
import uz.yukgo.order.OrderStatus;
import uz.yukgo.telegram.TelegramClient;
import uz.yukgo.user.BodyType;
import uz.yukgo.user.UserRepository;
import uz.yukgo.wallet.WalletDtos.PricingDto;
import uz.yukgo.wallet.WalletService;

import java.util.LinkedHashMap;
import java.util.Map;

/** Frontend va mobil ilova uchun ochiq sozlamalar. */
@RestController
@RequiredArgsConstructor
public class PublicController {
    private final AppProperties props;
    private final TelegramClient telegram;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final WalletService wallet;
    private final Environment env;
    private final ChannelFeedRepository channelFeeds;

    @GetMapping("/api/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("yandexMapsApiKey", props.yandex().mapsApiKey());
        m.put("botUsername", telegram.botUsername());
        m.put("botEnabled", telegram.enabled());
        m.put("devMode", props.devMode());
        m.put("cargoTypes", CargoType.values());
        m.put("bodyTypes", BodyType.values());
        // Komissiya va shoshilinch narxi (tizim o'chiq bo'lsa enabled=false — frontend hech narsa ko'rsatmaydi)
        m.put("pricing", PricingDto.of(wallet.current()));
        // Yangi e'lonlar Telegram kanallarga ham chiqadimi (formada foydalanuvchiga aytiladi)
        m.put("channelPosting", telegram.enabled() && channelFeeds.countByActiveTrue() > 0);
        // Maxfiylik siyosati va "hisobni o'chirish" sahifalaridagi aloqa (Railway: SUPPORT_EMAIL o'zgaruvchisi)
        String email = env.getProperty("SUPPORT_EMAIL");
        if (email != null && !email.isBlank()) m.put("supportEmail", email.trim());
        return m;
    }

    @GetMapping("/api/stats/public")
    @Transactional(readOnly = true)
    public Map<String, Object> publicStats() {
        return Map.of(
                "drivers", userRepository.countDrivers(),
                "delivered", orderRepository.countByStatus(OrderStatus.DELIVERED),
                "openOrders", orderRepository.countByStatus(OrderStatus.NEW));
    }
}
