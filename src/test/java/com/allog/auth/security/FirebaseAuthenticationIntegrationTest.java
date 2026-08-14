package com.allog.auth.security;

import com.allog.auth.firebase.FirebaseIdTokenVerifier;
import com.allog.auth.firebase.FirebaseVerificationException;
import com.allog.auth.firebase.VerifiedFirebaseToken;
import com.allog.user.domain.IdentityProvider;
import com.allog.user.domain.User;
import com.allog.user.domain.UserIdentity;
import com.allog.user.repository.UserIdentityRepository;
import com.allog.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "allog.auth.firebase.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FirebaseAuthenticationIntegrationTest.AuthTestConfiguration.class)
class FirebaseAuthenticationIntegrationTest {

    private static final String ENDPOINT = "/test/auth/principal";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubFirebaseIdTokenVerifier verifier;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserIdentityRepository identityRepository;

    @BeforeEach
    void cleanDatabaseAndVerifier() {
        identityRepository.deleteAll();
        userRepository.deleteAll();
        verifier.reset();
    }

    @Test
    void missingTokenReturns401WithoutDatabaseMutation() throws Exception {
        mockMvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());

        assertEquals(0, verifier.calls());
        assertEquals(0, userRepository.count());
        assertEquals(0, identityRepository.count());
    }

    @Test
    void malformedEmptyAndDuplicateAuthorizationHeadersReturn401WithoutVerification() throws Exception {
        mockMvc.perform(get(ENDPOINT).header(HttpHeaders.AUTHORIZATION, "abc"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Bearer"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(ENDPOINT).header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer first-token",
                        "Bearer second-token"
                ))
                .andExpect(status().isUnauthorized());

        assertEquals(0, verifier.calls());
        assertEquals(0, userRepository.count());
    }

    @Test
    void invalidTokenReturns401WithoutDatabaseMutation() throws Exception {
        verifier.invalid();

        mockMvc.perform(get(ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());

        assertEquals(1, verifier.calls());
        assertEquals(0, userRepository.count());
        assertEquals(0, identityRepository.count());
    }

    @Test
    void firebaseInfrastructureFailureReturns503WithoutDatabaseMutation() throws Exception {
        verifier.unavailable();

        mockMvc.perform(get(ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Bearer unavailable-token"))
                .andExpect(status().isServiceUnavailable());

        assertEquals(0, userRepository.count());
        assertEquals(0, identityRepository.count());
    }

    @Test
    void existingIdentityAuthenticatesWithoutCreatingRows() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        identityRepository.saveAndFlush(new UserIdentity(
                user,
                IdentityProvider.FIREBASE,
                "uid-existing"
        ));
        verifier.valid("uid-existing");

        mockMvc.perform(get(ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId()))
                .andExpect(jsonPath("$.authenticated").value(true));

        assertEquals(1, userRepository.count());
        assertEquals(1, identityRepository.count());
    }

    @Test
    void firstLoginCreatesCanonicalIdentityAndReloginUsesSameUser() throws Exception {
        verifier.valid("uid-new");

        mockMvc.perform(get(ENDPOINT)
                        .param("userId", "999")
                        .header("X-User-Id", "999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialsAbsent").value(true))
                .andExpect(jsonPath("$.authorityCount").value(0))
                .andExpect(jsonPath("$.sessionCreated").value(false));

        UserIdentity identity = identityRepository
                .findByProviderAndSubject(IdentityProvider.FIREBASE, "uid-new")
                .orElseThrow();
        Long userId = identity.getUser().getId();
        assertEquals(1, userRepository.count());
        assertEquals(1, identityRepository.count());

        mockMvc.perform(get(ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId));
        assertEquals(1, userRepository.count());
        assertEquals(1, identityRepository.count());
    }

    @Test
    void authenticationDoesNotPersistAcrossRequests() throws Exception {
        verifier.valid("uid-stateless");

        mockMvc.perform(get(ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                .andExpect(status().isOk());
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AuthTestConfiguration {

        @Bean
        @Primary
        StubFirebaseIdTokenVerifier stubFirebaseIdTokenVerifier() {
            return new StubFirebaseIdTokenVerifier();
        }

        @Bean
        AuthTestController authTestController() {
            return new AuthTestController();
        }
    }

    @RestController
    static class AuthTestController {

        @GetMapping(ENDPOINT)
        Map<String, Object> principal(
                @AuthenticationPrincipal AllogPrincipal principal,
                Authentication authentication,
                HttpServletRequest request
        ) {
            return Map.of(
                    "userId", principal.userId(),
                    "credentialsAbsent", authentication.getCredentials() == null,
                    "authorityCount", authentication.getAuthorities().size(),
                    "authenticated", authentication.isAuthenticated(),
                    "sessionCreated", request.getSession(false) != null
            );
        }
    }

    static class StubFirebaseIdTokenVerifier implements FirebaseIdTokenVerifier {

        private final AtomicInteger calls = new AtomicInteger();
        private Function<String, VerifiedFirebaseToken> behavior;

        void reset() {
            calls.set(0);
            invalid();
        }

        void valid(String uid) {
            behavior = token -> new VerifiedFirebaseToken(uid);
        }

        void invalid() {
            behavior = token -> {
                throw new FirebaseVerificationException(
                        FirebaseVerificationException.Reason.INVALID_TOKEN,
                        "invalid test token"
                );
            };
        }

        void unavailable() {
            behavior = token -> {
                throw new FirebaseVerificationException(
                        FirebaseVerificationException.Reason.UNAVAILABLE,
                        "test infrastructure unavailable"
                );
            };
        }

        int calls() {
            return calls.get();
        }

        @Override
        public VerifiedFirebaseToken verify(String idToken) {
            calls.incrementAndGet();
            return behavior.apply(idToken);
        }
    }
}
