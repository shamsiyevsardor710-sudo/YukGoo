package uz.yukgo.driver;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class DriverVerificationDtos {
    private DriverVerificationDtos() {}

    /**
     * Haydovchining o'z holati.
     * status: NONE — ariza yo'q, PENDING — ko'rib chiqilmoqda, APPROVED — tekshirilgan, REJECTED — rad etilgan (sababi bilan)
     */
    public record MyStatus(String status, boolean verified, String rejectReason, Instant submittedAt, Instant decidedAt) {}

    public record AdminItem(Long id, String status, Long userId, String fullName, String phone, String avatarUrl,
                            String vehicleInfo, String plateNumber, String vehiclePhotoUrl, double ratingAvg, int ratingCount,
                            int deliveriesCompleted, Instant userCreatedAt, Instant submittedAt, Instant decidedAt,
                            String decidedBy, String rejectReason, boolean filesAvailable) {}

    public record AdminList(List<AdminItem> pending, List<AdminItem> recent) {}

    public record RejectRequest(@Size(max = 300) String reason) {}

    public record FileData(String contentType, byte[] data) {}
}
