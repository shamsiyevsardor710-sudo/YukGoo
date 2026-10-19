package uz.yukgo.admin;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Admin va ega amallari jurnali — kim, kimni, qachon, nima uchun. */
@Entity
@Table(name = "admin_logs")
@Getter
@Setter
@NoArgsConstructor
public class AdminLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long actorId;
    private String actorName;

    @Column(nullable = false, length = 40)
    private String action;

    private Long targetUserId;
    private String targetName;

    @Column(length = 500)
    private String details;

    private Instant createdAt = Instant.now();
}
