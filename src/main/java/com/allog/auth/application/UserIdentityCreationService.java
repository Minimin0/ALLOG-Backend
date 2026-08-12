package com.allog.auth.application;

import com.allog.user.domain.IdentityProvider;
import com.allog.user.domain.User;
import com.allog.user.domain.UserIdentity;
import com.allog.user.repository.UserIdentityRepository;
import com.allog.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserIdentityCreationService {

    private final UserRepository userRepository;
    private final UserIdentityRepository identityRepository;

    public UserIdentityCreationService(
            UserRepository userRepository,
            UserIdentityRepository identityRepository
    ) {
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long create(IdentityProvider provider, String subject) {
        User user = userRepository.save(User.create());
        identityRepository.saveAndFlush(new UserIdentity(user, provider, subject));
        return user.getId();
    }
}
