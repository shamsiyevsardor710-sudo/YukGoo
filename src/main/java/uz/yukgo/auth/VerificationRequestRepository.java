package uz.yukgo.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, String> {
    Optional<VerificationRequest> findByLinkToken(String linkToken);

    List<VerificationRequest> findByPhoneAndConsumedFalseAndVerifiedFalseAndCreatedAtAfterOrderByCreatedAtDesc(
            String phone, Instant after);

    long countByPhoneAndCreatedAtAfter(String phone, Instant after);
}
