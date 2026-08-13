package com.allog.verification.analysis.domain;

import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerificationAnalysisTest {

    @Test
    void createsPendingAnalysisForSubmittedVerification() {
        Verification verification = verification(VerificationStatus.SUBMITTED);
        UUID requestId = UUID.randomUUID();

        VerificationAnalysis analysis = VerificationAnalysis.createPending(verification, requestId);

        assertAll(
                () -> assertSame(verification, analysis.getVerification()),
                () -> assertEquals(requestId, analysis.getAnalysisRequestId()),
                () -> assertEquals(VerificationAnalysisStatus.PENDING, analysis.getStatus()),
                () -> assertEquals(0, analysis.getAttemptCount()),
                () -> assertNull(analysis.getRecommendation()),
                () -> assertNull(analysis.getFailureCode()),
                () -> assertNull(analysis.getCompletedAt()),
                () -> assertNull(analysis.getObjectPresence()),
                () -> assertNull(analysis.getRelevanceScore()),
                () -> assertNull(analysis.getAnomalyDetected()),
                () -> assertNull(analysis.getFramedProperly())
        );
    }

    @Test
    void requiresVerificationAndBackendRequestId() {
        Verification submitted = verification(VerificationStatus.SUBMITTED);

        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> VerificationAnalysis.createPending(null, UUID.randomUUID())
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> VerificationAnalysis.createPending(submitted, null)
                )
        );
    }

    @Test
    void rejectsAnalysisBeforeSubmission() {
        Verification pending = verification(VerificationStatus.PENDING_UPLOAD);

        assertThrows(
                IllegalStateException.class,
                () -> VerificationAnalysis.createPending(pending, UUID.randomUUID())
        );
    }

    private Verification verification(VerificationStatus status) {
        Verification verification = mock(Verification.class);
        when(verification.getStatus()).thenReturn(status);
        return verification;
    }
}
