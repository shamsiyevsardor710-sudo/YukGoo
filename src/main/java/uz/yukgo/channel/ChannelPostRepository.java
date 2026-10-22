package uz.yukgo.channel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChannelPostRepository extends JpaRepository<ChannelPost, Long> {
    List<ChannelPost> findByOrderId(Long orderId);

    @Modifying
    @Query("update ChannelPost p set p.state = :state where p.id = :id")
    int updateState(@Param("id") Long id, @Param("state") String state);
}
