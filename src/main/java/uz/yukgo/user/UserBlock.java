package uz.yukgo.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Bir foydalanuvchi boshqasini bloklagan (qanday ta'sir qilishi — {@link BlockService}). */
@Entity
@Table(name = "user_blocks",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_blocks_pair", columnNames = {"blockerId", "blockedId"}),
        indexes = @Index(name = "idx_user_blocks_blocked", columnList = "blockedId"))
@Getter
@Setter
@NoArgsConstructor
public class UserBlock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Kim blokladi */
    @Column(nullable = false)
    private Long blockerId;

    /** Kim bloklandi */
    @Column(nullable = false)
    private Long blockedId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
