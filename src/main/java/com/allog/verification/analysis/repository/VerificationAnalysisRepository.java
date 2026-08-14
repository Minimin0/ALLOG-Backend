package com.allog.verification.analysis.repository;

import com.allog.verification.analysis.domain.VerificationAnalysis;
import com.allog.verification.analysis.domain.VerificationAnalysisStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VerificationAnalysisRepository extends JpaRepository<VerificationAnalysis, Long> {

    Optional<VerificationAnalysis> findByVerification_Id(Long verificationId);

    @Query("""
            select analysis
            from VerificationAnalysis analysis
            join fetch analysis.verification
            where analysis.id = :analysisId
            """)
    Optional<VerificationAnalysis> findByIdWithVerification(@Param("analysisId") Long analysisId);

    @Query("""
            select analysis.id
            from VerificationAnalysis analysis
            where analysis.status = :status
            order by analysis.id
            """)
    List<Long> findPendingCandidateIds(
            @Param("status") VerificationAnalysisStatus status,
            Pageable pageable
    );

    @Query("""
            select analysis.id
            from VerificationAnalysis analysis
            where analysis.status = :status
              and analysis.lastAttemptAt <= :staleAtOrBefore
            order by analysis.lastAttemptAt, analysis.id
            """)
    List<Long> findStaleProcessingCandidateIds(
            @Param("status") VerificationAnalysisStatus status,
            @Param("staleAtOrBefore") Instant staleAtOrBefore,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select analysis from VerificationAnalysis analysis where analysis.id = :analysisId")
    Optional<VerificationAnalysis> findByIdForUpdate(@Param("analysisId") Long analysisId);
}
