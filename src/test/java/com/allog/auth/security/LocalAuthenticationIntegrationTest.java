package com.allog.auth.security;

import com.allog.user.domain.IdentityProvider;
import com.allog.user.domain.UserIdentity;
import com.allog.user.repository.UserIdentityRepository;
import com.allog.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocalAuthenticationIntegrationTest.AuthTestConfiguration.class)
class LocalAuthenticationIntegrationTest {

    private static final String SIGNUP = "/api/v1/auth/signup";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PRINCIPAL = "/test/auth/principal";
    private static final String PASSWORD = "JudgePass123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserIdentityRepository identityRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AccessTokenService tokenService;

    @Autowired
    private JwtDecoder jwtDecoder;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        identityRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void signupNormalizesLoginIdHashesPasswordAndReturnsBoundedToken() throws Exception {
        String body = mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("  Allog_User  ", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(86400))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(body).path("accessToken").asText();
        UserIdentity identity = identityRepository
                .findByProviderAndSubject(IdentityProvider.LOCAL, "allog_user")
                .orElseThrow();
        assertNotEquals(PASSWORD, identity.getPasswordHash());
        assertTrue(passwordEncoder.matches(PASSWORD, identity.getPasswordHash()));
        assertEquals(identity.getUser().getId().toString(), jwtDecoder.decode(token).getSubject());
        assertEquals(4, jwtDecoder.decode(token).getClaims().size());
    }

    @Test
    void duplicateAndInvalidSignupUseStableClientErrors() throws Exception {
        signup("judge_user", PASSWORD);

        mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("JUDGE_USER", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LOGIN_ID_ALREADY_EXISTS"));
        mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("ab", PASSWORD)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("valid_id", "short")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginUsesTheSameGeneric401ForUnknownIdAndWrongPassword() throws Exception {
        signup("judge_user", PASSWORD);
        String expected = "아이디 또는 비밀번호가 올바르지 않아요.";

        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("unknown_user", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").value(expected));
        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("judge_user", "WrongPass123!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").value(expected));
    }

    @Test
    void validLoginTokenAuthenticatesTheCorrectPrincipalStatelessly() throws Exception {
        signup("judge_user", PASSWORD);
        UserIdentity identity = identityRepository
                .findByProviderAndSubject(IdentityProvider.LOCAL, "judge_user")
                .orElseThrow();
        String token = login("judge_user", PASSWORD);

        mockMvc.perform(get(PRINCIPAL)
                        .param("userId", "999")
                        .header("X-User-Id", "999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(identity.getUser().getId()))
                .andExpect(jsonPath("$.credentialsAbsent").value(true))
                .andExpect(jsonPath("$.authorityCount").value(0))
                .andExpect(jsonPath("$.sessionCreated").value(false));
        mockMvc.perform(get(PRINCIPAL)).andExpect(status().isUnauthorized());
    }

    @Test
    void missingMalformedExpiredAndWrongSignatureTokensReturn401() throws Exception {
        String valid = signup("judge_user", PASSWORD);
        String wrongSignature = valid.substring(0, valid.length() - 1)
                + (valid.endsWith("A") ? "B" : "A");
        String expired = tokenService.issueAt(7L, Instant.now().minus(Duration.ofDays(2))).value();

        mockMvc.perform(get(PRINCIPAL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PRINCIPAL).header(HttpHeaders.AUTHORIZATION, "abc"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PRINCIPAL).header(HttpHeaders.AUTHORIZATION, "Bearer malformed"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PRINCIPAL).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PRINCIPAL).header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongSignature))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PRINCIPAL).header(HttpHeaders.AUTHORIZATION, "Bearer " + valid, "Bearer " + valid))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAuthEndpointsArePublicAndAdminRemainsProtected() throws Exception {
        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("unknown_user", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/verifications/pending-review"))
                .andExpect(status().isUnauthorized());
        assertFalse(identityRepository.existsByProviderAndSubject(IdentityProvider.LOCAL, "unknown_user"));
    }

    private String signup(String loginId, String password) throws Exception {
        return token(post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(credentials(loginId, password)), 201);
    }

    private String login(String loginId, String password) throws Exception {
        return token(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(credentials(loginId, password)), 200);
    }

    private String token(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int status)
            throws Exception {
        String body = mockMvc.perform(request)
                .andExpect(status().is(status))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("accessToken").asText();
    }

    private String credentials(String loginId, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("loginId", loginId, "password", password));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AuthTestConfiguration {
        @Bean
        AuthTestController authTestController() {
            return new AuthTestController();
        }
    }

    @RestController
    static class AuthTestController {
        @GetMapping(PRINCIPAL)
        Map<String, Object> principal(
                @AuthenticationPrincipal AllogPrincipal principal,
                Authentication authentication,
                HttpServletRequest request
        ) {
            return Map.of(
                    "userId", principal.userId(),
                    "credentialsAbsent", authentication.getCredentials() == null,
                    "authorityCount", authentication.getAuthorities().size(),
                    "sessionCreated", request.getSession(false) != null
            );
        }
    }
}
