package com.allog.heart.domain;

import com.allog.common.persistence.BaseTimeEntity;
import com.allog.user.domain.User;
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

import java.util.Objects;

/**
 * A member's current heart balance, and the only thing anyone reads to answer "how many do I have".
 * The ledger explains how the balance got here; this row is what it is now.
 *
 * <p>Balance changes are deliberately not expressed as entity setters. A read-then-write through JPA
 * cannot make "spend only if there is enough" safe under concurrency, so spending goes through a
 * single conditional UPDATE in the repository instead.
 */
@Entity
@Table(
        name = "heart_wallet",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_heart_wallet_user",
                columnNames = "user_id"
        )
)
public class HeartWallet extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "balance", nullable = false)
    private int balance;

    protected HeartWallet() {
    }

    private HeartWallet(User user, int balance) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        if (balance < 0) {
            throw new IllegalArgumentException("balance must not be negative");
        }
        this.balance = balance;
    }

    public static HeartWallet openWith(User user, int balance) {
        return new HeartWallet(user, balance);
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public int getBalance() {
        return balance;
    }
}
