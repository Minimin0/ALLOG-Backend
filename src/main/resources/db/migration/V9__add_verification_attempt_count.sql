ALTER TABLE verification
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;

UPDATE verification
   SET attempt_count = 1
 WHERE status <> 'PENDING_UPLOAD';

ALTER TABLE verification
    ADD CONSTRAINT chk_verification_attempt_count
        CHECK (attempt_count >= 0);
