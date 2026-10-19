package uz.yukgo.telegram;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Telefon raqam ↔ Telegram chat bog'lanishi.
 * Foydalanuvchi botga o'z kontaktini ulashganda yaratiladi (ro'yxatdan o'tishdan oldin ham).
 */
@Entity
@Table(name = "telegram_links")
@Getter
@Setter
@NoArgsConstructor
public class TelegramLink {
    @Id
    @Column(length = 20)
    private String phone;

    @Column(nullable = false)
    private Long chatId;

    private Long telegramUserId;
    private String username;
    private Instant linkedAt = Instant.now();
}
