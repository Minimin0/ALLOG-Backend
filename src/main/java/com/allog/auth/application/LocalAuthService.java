package com.allog.auth.application;

import com.allog.auth.security.AccessTokenService;
import com.allog.user.domain.IdentityProvider;
import com.allog.user.domain.UserIdentity;
import com.allog.user.repository.UserIdentityRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class LocalAuthService {

    private final UserIdentityRepository identityRepository;
    private final LocalAccountCreationService accountCreationService;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService tokenService;
    private final String dummyHash;

    public LocalAuthService(
            UserIdentityRepository identityRepository,
            LocalAccountCreationService accountCreationService,
            PasswordEncoder passwordEncoder,
            AccessTokenService tokenService
    ) {
        this.identityRepository = identityRepository;
        this.accountCreationService = accountCreationService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public AuthResult signup(String loginId, String password) {
        if (exists(loginId)) throw new DuplicateLoginIdException();
        String passwordHash = passwordEncoder.encode(password);
        try {
            return token(accountCreationService.create(loginId, passwordHash));
        } catch (DataIntegrityViolationException exception) {
            if (exists(loginId)) throw new DuplicateLoginIdException();
            throw exception;
        }
    }

    public AuthResult login(String loginId, String password) {
        UserIdentity identity = identityRepository
                .findByProviderAndSubject(IdentityProvider.LOCAL, loginId)
                .orElse(null);
        String passwordHash = identity == null ? dummyHash : identity.getPasswordHash();
        if (!passwordEncoder.matches(password, passwordHash)) throw new InvalidCredentialsException();
        return token(identity.getUser().getId());
    }

    private boolean exists(String loginId) {
        return identityRepository.existsByProviderAndSubject(IdentityProvider.LOCAL, loginId);
    }

    private AuthResult token(Long userId) {
        AccessTokenService.IssuedToken token = tokenService.issue(userId);
        return new AuthResult(token.value(), "Bearer", token.expiresInSeconds());
    }

    public record AuthResult(String accessToken, String tokenType, long expiresInSeconds) {
    }
}
