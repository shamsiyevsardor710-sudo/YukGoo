package uz.yukgo.telegram;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TelegramLinkRepository extends JpaRepository<TelegramLink, String> {
    List<TelegramLink> findByChatId(Long chatId);
}
