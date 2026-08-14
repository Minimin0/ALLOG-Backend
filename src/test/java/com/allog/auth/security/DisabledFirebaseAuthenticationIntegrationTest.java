package com.allog.auth.security;

import com.allog.user.repository.UserIdentityRepository;
import com.allog.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "allog.auth.firebase.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DisabledFirebaseAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserIdentityRepository identityRepository;

    @BeforeEach
    void cleanDatabase() {
        identityRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void bearerRequestFailsClosedWhenFirebaseIsDisabled() throws Exception {
        mockMvc.perform(get("/protected-resource")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer any-token"))
                .andExpect(status().isServiceUnavailable());

        assertEquals(0, userRepository.count());
        assertEquals(0, identityRepository.count());
    }
}
