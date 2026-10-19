package uz.yukgo.review;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ReviewController {
    private final ReviewService reviewService;

    @PostMapping("/api/orders/{orderId}/review")
    public ReviewService.ReviewDto create(@PathVariable Long orderId, @Valid @RequestBody ReviewService.ReviewRequest req) {
        return reviewService.create(orderId, req);
    }

    @GetMapping("/api/users/{userId}/reviews")
    public ReviewService.ReviewSummary forUser(@PathVariable Long userId, @RequestParam(defaultValue = "0") int page) {
        return reviewService.forUser(userId, page);
    }
}
