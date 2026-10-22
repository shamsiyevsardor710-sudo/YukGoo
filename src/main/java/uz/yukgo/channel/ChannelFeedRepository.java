package uz.yukgo.channel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChannelFeedRepository extends JpaRepository<ChannelFeed, Long> {
    List<ChannelFeed> findByActiveTrue();

    long countByActiveTrue();

    List<ChannelFeed> findAllByOrderByCreatedAtAsc();

    Optional<ChannelFeed> findByChatId(Long chatId);

    /** Postlar soni — boshqa ustunlarga tegmasdan (admin shu paytda kanalni o'zgartirsa ham uning o'zgarishi yo'qolmaydi) */
    @Modifying
    @Query("update ChannelFeed f set f.postsCount = f.postsCount + 1 where f.id = :id")
    int incrementPosts(@Param("id") Long id);
}
