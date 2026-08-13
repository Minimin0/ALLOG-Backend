CREATE TABLE verification_analysis (
    id BIGINT NOT NULL AUTO_INCREMENT,
    verification_id BIGINT NOT NULL,
    analysis_request_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    recommendation VARCHAR(32),
    reason_code VARCHAR(64),
    provider_model VARCHAR(100),
    criteria_version VARCHAR(64),
    object_presence BOOLEAN,
    relevance_score DECIMAL(5,4),
    anomaly_detected BOOLEAN,
    framed_properly BOOLEAN,
    failure_code VARCHAR(32),
    attempt_count INT NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_verification_analysis_verification UNIQUE (verification_id),
    CONSTRAINT uk_verification_analysis_request UNIQUE (analysis_request_id),
    CONSTRAINT fk_verification_analysis_verification
        FOREIGN KEY (verification_id) REFERENCES verification (id),
    CONSTRAINT chk_verification_analysis_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_verification_analysis_recommendation
        CHECK (
            recommendation IS NULL
            OR recommendation IN ('PASS', 'REVIEW_REQUIRED', 'REJECT_CANDIDATE')
        ),
    CONSTRAINT chk_verification_analysis_failure_code
        CHECK (
            failure_code IS NULL
            OR failure_code IN (
                'TIMEOUT',
                'NETWORK',
                'RATE_LIMITED',
                'PROVIDER_5XX',
                'AUTHENTICATION',
                'BAD_REQUEST',
                'INVALID_RESPONSE',
                'INTERRUPTED'
            )
        ),
    CONSTRAINT chk_verification_analysis_attempt_count
        CHECK (attempt_count >= 0),
    CONSTRAINT chk_verification_analysis_relevance_score
        CHECK (relevance_score IS NULL OR relevance_score BETWEEN 0 AND 1),
    CONSTRAINT chk_verification_analysis_terminal
        CHECK (
            (
                status IN ('PENDING', 'PROCESSING')
                AND completed_at IS NULL
                AND recommendation IS NULL
                AND failure_code IS NULL
            )
            OR
            (
                status = 'SUCCEEDED'
                AND completed_at IS NOT NULL
                AND recommendation IS NOT NULL
                AND failure_code IS NULL
            )
            OR
            (
                status = 'FAILED'
                AND completed_at IS NOT NULL
                AND recommendation IS NULL
                AND failure_code IS NOT NULL
            )
        )
);

CREATE INDEX idx_verification_analysis_poll
    ON verification_analysis (status, last_attempt_at, id);
