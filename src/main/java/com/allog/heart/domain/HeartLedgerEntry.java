package com.allog.heart.domain;

import com.allog.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * One movement of hearts, written once and never changed. Together the entries explain the wallet
 * balance; {@code SUM(amount)} equalling the wallet is an integrity check, not something a request
 * computes.
 *
 * <p>Amounts are signed - a spend is negative - so the sum is the whole story with no direction
 * column to read alongside it.
 *
 * <p>{@code (type, sourceId)} is unique, which is what makes every grant, spend and refund
 * exactly-once: a replayed operation collides with the row it already wrote.
 */
@Entity
@Table(
        name = "heart_ledger_entry",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_heart_ledger_type_source",
                columnNames = {"type", "source_id"}
        ),
        indexes = @Index(
                name = "idx_heart_ledger_user_created",
                columnList = "user_id,created_at"
        )
)
public class HeartLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private HeartTransactionType type;

    @Column(name = "amount", nullable = false)
    private int amount;

    /** A user_profile id for a grant, a group_member id for a spend or refund. */
    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected HeartLedgerEntry() {
    }

    private HeartLedgerEntry(User user, HeartTransactionType type, int amount, Long sourceId) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
        this.amount = amount;
    }

    /**
     * Callers pass a positive magnitude and say what happened; the sign follows from the type, so a
     * spend cannot be recorded as a credit by passing the wrong number.
     */
    public static HeartLedgerEntry record(
            User user,
            HeartTransactionType type,
            int magnitude,
            Long sourceId
    ) {
        Objects.requireNonNull(type, "type must not be null");
        if (magnitude <= 0) {
            throw new IllegalArgumentException("magnitude must be positive");
        }
        return new HeartLedgerEntry(user, type, type.isCredit() ? magnitude : -magnitude, sourceId);
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public HeartTransactionType getType() {
        return type;
    }

    /** Signed: negative for a spend. */
    public int getAmount() {
        return amount;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
