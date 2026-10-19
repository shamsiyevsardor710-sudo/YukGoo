package uz.yukgo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        boolean devMode,
        String publicUrl,
        Jwt jwt,
        Telegram telegram,
        Yandex yandex,
        Owner owner,
        String uploadsDir,
        Verification verification,
        Pricing pricing
) {
    public record Jwt(String secret, int expirationHours) {}

    public record Telegram(String botToken, String botUsername) {
        public boolean enabled() {
            return botToken != null && !botToken.isBlank();
        }
    }

    public record Yandex(String mapsApiKey) {}

    public record Owner(String phone, String firstName, String lastName, String password) {}

    public record Verification(int codeTtlMinutes, int maxAttempts) {}

    public record Pricing(long cityBase, long intercityBase, long perKmLight,
                          long perKmMedium, long perKmHeavy, long perKmTruck) {}
}
