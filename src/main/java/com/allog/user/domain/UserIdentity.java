package com.allog.user.domain;

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

@Entity
@Table(
        name = "user_identity",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_identity_provider_subject",
                columnNames = {"provider", "subject"}
        ),
        indexes = @Index(name = "idx_user_identity_user_id", columnList = "user_id")
)
public class UserIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IdentityProvider provider;

    @Column(nullable = false, length = 255)
    private String subject;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserIdentity() {
    }

    public UserIdentity(User user, IdentityProvider provider, String subject) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        this.subject = subject;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public IdentityProvider getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
