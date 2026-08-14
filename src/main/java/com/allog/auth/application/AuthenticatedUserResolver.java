package com.allog.auth.application;

import com.allog.user.domain.IdentityProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedUserResolver {

    private final UserIdentityLookupService lookupService;
    private final UserIdentityCreationService creationService;

    public AuthenticatedUserResolver(
            UserIdentityLookupService lookupService,
            UserIdentityCreationService creationService
    ) {
        this.lookupService = lookupService;
        this.creationService = creationService;
    }

    public Long resolveOrCreate(IdentityProvider provider, String subject) {
        return lookupService.findUserId(provider, subject)
                .orElseGet(() -> createOrRecover(provider, subject));
    }

    private Long createOrRecover(IdentityProvider provider, String subject) {
        try {
            return creationService.create(provider, subject);
        } catch (DataIntegrityViolationException creationFailure) {
            return lookupService.findUserId(provider, subject)
                    .orElseThrow(() -> creationFailure);
        }
    }
}
