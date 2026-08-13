package com.allog.verification.analysis.repository;

import com.allog.verification.analysis.domain.VerificationAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VerificationAnalysisRepository extends JpaRepository<VerificationAnalysis, Long> {

    Optional<VerificationAnalysis> findByVerification_Id(Long verificationId);
}
