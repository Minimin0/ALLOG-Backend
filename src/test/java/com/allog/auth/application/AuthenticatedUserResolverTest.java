package com.allog.auth.application;

import com.allog.user.domain.IdentityProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticatedUserResolverTest {

    @Mock
    private UserIdentityLookupService lookupService;

    @Mock
    private UserIdentityCreationService creationService;

    @InjectMocks
    private AuthenticatedUserResolver resolver;

    @Test
    void returnsExistingUserWithoutCreation() {
        when(lookupService.findUserId(IdentityProvider.FIREBASE, "existing-uid"))
                .thenReturn(Optional.of(10L));

        assertEquals(10L, resolver.resolveOrCreate(IdentityProvider.FIREBASE, "existing-uid"));
        verify(creationService, never()).create(IdentityProvider.FIREBASE, "existing-uid");
    }

    @Test
    void recoversConcurrentWinnerInAFreshLookup() {
        DataIntegrityViolationException raceLoss = new DataIntegrityViolationException("duplicate identity");
        when(lookupService.findUserId(IdentityProvider.FIREBASE, "race-uid"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(20L));
        when(creationService.create(IdentityProvider.FIREBASE, "race-uid"))
                .thenThrow(raceLoss);

        assertEquals(20L, resolver.resolveOrCreate(IdentityProvider.FIREBASE, "race-uid"));
    }

    @Test
    void doesNotHideUnrelatedConstraintFailure() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("database failure");
        when(lookupService.findUserId(IdentityProvider.FIREBASE, "failed-uid"))
                .thenReturn(Optional.empty());
        when(creationService.create(IdentityProvider.FIREBASE, "failed-uid"))
                .thenThrow(failure);

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> resolver.resolveOrCreate(IdentityProvider.FIREBASE, "failed-uid")
        );
        assertSame(failure, thrown);
    }
}
