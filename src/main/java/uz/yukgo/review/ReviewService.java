package uz.yukgo.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.common.ApiException;
import uz.yukgo.common.PageResponse;
import uz.yukgo.notification.NotificationService;
import uz.yukgo.order.Order;
import uz.yukgo.order.OrderRepository;
import uz.yukgo.order.OrderStatus;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.user.User;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReviewService {
    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final CurrentUser currentUser;
    private final NotificationService notifications;

    public record ReviewRequest(
            @Min(value = 1, message = "Baho 1 dan 5 gacha") @Max(value = 5, message = "Baho 1 dan 5 gacha") int stars,
            @Size(max = 1000) String comment) {}

    public record ReviewDto(Long id, Long orderId, Long authorId, String authorName, String authorAvatar,
                            int stars, String comment, boolean targetWasDriver, Instant createdAt) {
        public static ReviewDto of(Review r) {
            return new ReviewDto(r.getId(), r.getOrderId(), r.getAuthor().getId(), r.getAuthor().getFullName(),
                    r.getAuthor().getAvatarUrl(), r.getStars(), r.getComment(), r.isTargetWasDriver(), r.getCreatedAt());
        }
    }

    public record ReviewSummary(double average, int count, Map<Integer, Long> distribution,
                                PageResponse<ReviewDto> reviews) {}

    @Transactional
    public ReviewDto create(Long orderId, ReviewRequest req) {
        User me = currentUser.get();
        Order o = orderRepository.findById(orderId).orElseThrow(() -> ApiException.notFound("Buyurtma topilmadi"));
        if (o.getStatus() != OrderStatus.DELIVERED) {
            throw ApiException.badRequest("Faqat yetkazilgan buyurtmani baholash mumkin");
        }
        boolean iAmSender = o.getSender().getId().equals(me.getId());
        boolean iAmDriver = o.getDriver() != null && o.getDriver().getId().equals(me.getId());
        if (!iAmSender && !iAmDriver) throw ApiException.forbidden("Siz bu buyurtma ishtirokchisi emassiz");
        if (reviewRepository.existsByOrderIdAndAuthorId(orderId, me.getId())) {
            throw ApiException.conflict("Siz bu buyurtmani allaqachon baholagansiz");
        }
        User target = iAmSender ? o.getDriver() : o.getSender();

        Review r = new Review();
        r.setOrderId(orderId);
        r.setAuthor(me);
        r.setTarget(target);
        r.setStars(req.stars());
        r.setComment(req.comment() == null || req.comment().isBlank() ? null : req.comment().trim());
        r.setTargetWasDriver(iAmSender);
        reviewRepository.save(r);

        target.setRatingSum(target.getRatingSum() + req.stars());
        target.setRatingCount(target.getRatingCount() + 1);

        notifications.notify(target, "Sizga yangi baho: " + "⭐".repeat(req.stars()),
                me.getFullName() + " sizni #" + orderId + " buyurtma bo'yicha baholadi"
                        + (r.getComment() != null ? ": \"" + r.getComment() + "\"" : ""),
                "#/u/" + target.getId());
        return ReviewDto.of(r);
    }

    @Transactional(readOnly = true)
    public ReviewSummary forUser(Long userId, int page) {
        var reviews = reviewRepository.findByTargetIdOrderByCreatedAtDesc(userId, PageRequest.of(page, 20));
        Map<Integer, Long> dist = new LinkedHashMap<>();
        for (int i = 5; i >= 1; i--) dist.put(i, 0L);
        long sum = 0, count = 0;
        for (Object[] row : reviewRepository.starDistribution(userId)) {
            int stars = ((Number) row[0]).intValue();
            long c = ((Number) row[1]).longValue();
            dist.put(stars, c);
            sum += stars * c;
            count += c;
        }
        double avg = count == 0 ? 0 : Math.round((double) sum / count * 10) / 10.0;
        return new ReviewSummary(avg, (int) count, dist, PageResponse.of(reviews, ReviewDto::of));
    }

    /** Admin: nomaqbul sharhni o'chirish va reytingni qayta hisoblash */
    @Transactional
    public void delete(Long reviewId) {
        Review r = reviewRepository.findById(reviewId).orElseThrow(() -> ApiException.notFound("Sharh topilmadi"));
        User t = r.getTarget();
        t.setRatingSum(Math.max(0, t.getRatingSum() - r.getStars()));
        t.setRatingCount(Math.max(0, t.getRatingCount() - 1));
        reviewRepository.delete(r);
    }
}
