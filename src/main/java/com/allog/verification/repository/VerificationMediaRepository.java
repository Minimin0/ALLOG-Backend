package com.allog.verification.repository;

import com.allog.verification.domain.VerificationMedia;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface VerificationMediaRepository extends JpaRepository<VerificationMedia, Long> {

    Optional<VerificationMedia> findByVerification_Id(Long verificationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select media from VerificationMedia media where media.verification.id = :verificationId")
    Optional<VerificationMedia> findByVerificationIdForUpdate(@Param("verificationId") Long verificationId);
}
