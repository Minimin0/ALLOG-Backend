package com.allog.auth.application;

import com.allog.user.domain.IdentityProvider;
import com.allog.user.repository.UserIdentityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UserIdentityLookupService {

    private final UserIdentityRepository identityRepository;

    public UserIdentityLookupService(UserIdentityRepository identityRepository) {
        this.identityRepository = identityRepository;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> findUserId(IdentityProvider provider, String subject) {
        return identityRepository.findByProviderAndSubject(provider, subject)
                .map(identity -> identity.getUser().getId());
    }
}
