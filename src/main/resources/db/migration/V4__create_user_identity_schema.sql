CREATE TABLE user_identity (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_identity_provider_subject UNIQUE (provider, subject),
    CONSTRAINT fk_user_identity_user
        FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_user_identity_user_id ON user_identity (user_id);
