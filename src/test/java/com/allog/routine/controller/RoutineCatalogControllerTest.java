package com.allog.routine.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.FirebaseBearerAuthenticationToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Group creation takes a {@code routineDefinitionId}. Before V17 the table was empty and nothing
 * exposed it, so a client had no id to send — these are the two halves of that fix.
 */
@SpringBootTest(properties = "allog.auth.firebase.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoutineCatalogControllerTest {

    private static final String ROUTINES = "/api/v1/routines";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v17SeedsTheRoutineCategoriesOnboardingAlreadyOffers() {
        List<String> keys = jdbcTemplate.queryForList(
                "SELECT routine_key FROM routine_definition WHERE routine_key IS NOT NULL ORDER BY routine_key",
                String.class);

        assertEquals(List.of("EXERCISE", "HYDRATION", "MEAL", "SKINCARE", "SLEEP"), keys);
    }

    @Test
    void everySeededRowIsUsableAsARoutineDefinitionId() {
        Integer nameable = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routine_definition WHERE routine_key IS NOT NULL"
                        + " AND name IS NOT NULL AND description IS NOT NULL",
                Integer.class);

        assertEquals(5, nameable, "a row with no name cannot be shown in a picker");
    }

    @Test
    void listsTheCatalogueWithTheIdGroupCreationTakes() throws Exception {
        mockMvc.perform(get(ROUTINES).with(authentication(
                        FirebaseBearerAuthenticationToken.authenticated(new AllogPrincipal(42L)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].routineDefinitionId").isNumber())
                .andExpect(jsonPath("$.items[0].routineKey").value("HYDRATION"))
                .andExpect(jsonPath("$.items[0].name").value("물 마시기"));
    }

    /** The catalogue is not a public surface: it sits behind the same bearer token as everything else. */
    @Test
    void unauthenticatedRequestsAreRefused() throws Exception {
        mockMvc.perform(get(ROUTINES)).andExpect(status().isUnauthorized());
    }
}
