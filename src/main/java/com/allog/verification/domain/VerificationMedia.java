package com.allog.verification.domain;

import com.allog.common.persistence.BaseTimeEntity;
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

@Entity
@Table(
        name = "verification_media",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_verification_media_verification",
                        columnNames = "verification_id"
                ),
                @UniqueConstraint(
                        name = "uk_verification_media_object_key",
                        columnNames = "object_key"
                )
        }
)
public class VerificationMedia extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "verification_id", nullable = false)
    private Verification verification;

    @Column(name = "object_key", nullable = false, length = 255)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "expected_size_bytes", nullable = false)
    private long expectedSizeBytes;

    @Column(name = "confirmed_size_bytes")
    private Long confirmedSizeBytes;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    protected VerificationMedia() {
    }

    private VerificationMedia(
            Verification verification,
            String objectKey,
            String contentType,
            long expectedSizeBytes
    ) {
        this.verification = Objects.requireNonNull(verification, "verification must not be null");
        this.objectKey = requireText(objectKey, "objectKey");
        this.contentType = requireText(contentType, "contentType");
        if (expectedSizeBytes <= 0) {
            throw new IllegalArgumentException("expectedSizeBytes must be positive");
        }
        this.expectedSizeBytes = expectedSizeBytes;
    }

    public static VerificationMedia create(
            Verification verification,
            String objectKey,
            String contentType,
            long expectedSizeBytes
    ) {
        return new VerificationMedia(verification, objectKey, contentType, expectedSizeBytes);
    }

    public void confirm(long actualSizeBytes, Clock clock) {
        if (actualSizeBytes <= 0) {
            throw new IllegalArgumentException("actualSizeBytes must be positive");
        }
        if (actualSizeBytes != expectedSizeBytes) {
            throw new IllegalStateException("confirmed media size must equal expected size");
        }
        Instant confirmationTime = Objects.requireNonNull(
                Objects.requireNonNull(clock, "clock must not be null").instant(),
                "clock instant must not be null"
        );
        if (confirmedAt != null || confirmedSizeBytes != null) {
            if (confirmedAt != null && Objects.equals(confirmedSizeBytes, actualSizeBytes)) {
                return;
            }
            throw new IllegalStateException("media cannot be reconfirmed with different metadata");
        }
        confirmedSizeBytes = actualSizeBytes;
        confirmedAt = confirmationTime;
    }

    public Long getId() {
        return id;
    }

    public Verification getVerification() {
        return verification;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public long getExpectedSizeBytes() {
        return expectedSizeBytes;
    }

    public Long getConfirmedSizeBytes() {
        return confirmedSizeBytes;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public boolean isConfirmed() {
        return confirmedAt != null && confirmedSizeBytes != null;
    }

    private String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
