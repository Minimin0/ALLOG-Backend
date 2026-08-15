package com.allog.reward.domain;

import com.allog.common.persistence.BaseTimeEntity;
import com.allog.verification.domain.Verification;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * The points one approved verification earned. One row per verification, enforced by a unique key,
 * so an approval can be replayed without paying twice.
 *
 * <p>The ledger is append-only in this MVP: an approval that is later invalidated keeps its row.
 * Clawback is a product decision that has not been made, and silently subtracting points would be
 * a worse answer than not subtracting them.
 *
 * <p>Who earned it is deliberately not copied here - it is the verification's member, one join away.
 */
@Entity
@Table(
        name = "verification_reward",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_verification_reward_verification",
                columnNames = "verification_id"
        )
)
public class VerificationReward extends BaseTimeEntity {

    /** Flat rate for the MVP. Tiering is a product decision, not a technical default. */
    private static final int POINTS_PER_APPROVAL = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "verification_id", nullable = false)
    private Verification verification;

    @Column(name = "points", nullable = false)
    private int points;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    protected VerificationReward() {
    }

    private VerificationReward(Verification verification, int points, Instant grantedAt) {
        this.verification = Objects.requireNonNull(verification, "verification must not be null");
        this.points = points;
        this.grantedAt = Objects.requireNonNull(grantedAt, "grantedAt must not be null");
    }

    public static VerificationReward grant(Verification verification, Clock clock) {
        if (!Objects.requireNonNull(verification, "verification must not be null")
                .getStatus()
                .countsAsProgress()) {
            throw new IllegalStateException("only an approved verification earns a reward");
        }
        return new VerificationReward(
                verification,
                POINTS_PER_APPROVAL,
                Objects.requireNonNull(clock, "clock must not be null").instant()
        );
    }

    public Long getId() {
        return id;
    }

    public Verification getVerification() {
        return verification;
    }

    public int getPoints() {
        return points;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }
}
