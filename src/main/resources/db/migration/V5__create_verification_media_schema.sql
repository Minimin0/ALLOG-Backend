CREATE TABLE verification_media (
    id BIGINT NOT NULL AUTO_INCREMENT,
    verification_id BIGINT NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    expected_size_bytes BIGINT NOT NULL,
    confirmed_size_bytes BIGINT,
    confirmed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_verification_media_verification UNIQUE (verification_id),
    CONSTRAINT uk_verification_media_object_key UNIQUE (object_key),
    CONSTRAINT fk_verification_media_verification
        FOREIGN KEY (verification_id) REFERENCES verification (id),
    CONSTRAINT chk_verification_media_expected_size
        CHECK (expected_size_bytes > 0),
    CONSTRAINT chk_verification_media_confirmed_size
        CHECK (confirmed_size_bytes IS NULL OR confirmed_size_bytes > 0),
    CONSTRAINT chk_verification_media_confirmation_pair
        CHECK (
            (confirmed_at IS NULL AND confirmed_size_bytes IS NULL)
            OR
            (confirmed_at IS NOT NULL AND confirmed_size_bytes IS NOT NULL)
        )
);
