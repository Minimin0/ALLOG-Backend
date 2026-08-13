package com.allog.ai.coaching.production;

import com.allog.group.domain.GroupMemberStatus;
import com.allog.progress.domain.AuthoritativeProgressFacts;
import com.allog.progress.domain.GroupProgressFacts;
import com.allog.progress.domain.PersonalProgressFacts;
import com.allog.progress.service.AuthoritativeProgressQueryService;
import com.allog.progress.service.ProgressNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionAiCoachQueryServiceTest {

    private static final Long GROUP_ID = 1L;
    private static final Long USER_ID = 2L;

    @Mock
    private AuthoritativeProgressQueryService progressQueryService;

    private ProductionAiCoachQueryService service;

    @BeforeEach
    void setUp() {
        service = new ProductionAiCoachQueryService(progressQueryService);
    }

    @Test
    void mapsCoreActiveFactsWithoutRecalculatingProgress() {
        PersonalProgressFacts personal = new PersonalProgressFacts(
                true,
                false,
                false,
                3,
                5,
                2,
                1,
                2,
                0,
                Optional.of(Instant.parse("2026-08-11T14:00:00Z")),
                GroupMemberStatus.ACTIVE
        );
        GroupProgressFacts group = new GroupProgressFacts(2, 8, 10, 0.8, 0, 1);
        when(progressQueryService.load(GROUP_ID, USER_ID))
                .thenReturn(AuthoritativeProgressFacts.active("아침 물 마시기", personal, group));

        ProductionAiCoachFacts result = service.load(GROUP_ID, USER_ID);

        assertEquals("아침 물 마시기", result.challengeName());
        assertEquals(personal, result.personalProgress().orElseThrow());
        assertEquals(group, result.groupProgress().orElseThrow());
    }

    @Test
    void mapsCoreLifecycleFacts() {
        when(progressQueryService.load(GROUP_ID, USER_ID)).thenReturn(
                AuthoritativeProgressFacts.lifecycle("아침 물 마시기", GroupMemberStatus.JOINED)
        );

        ProductionAiCoachFacts result = service.load(GROUP_ID, USER_ID);

        assertEquals(GroupMemberStatus.JOINED, result.participationStatus());
        assertEquals(Optional.empty(), result.personalProgress());
        assertEquals(Optional.empty(), result.groupProgress());
    }

    @Test
    void preservesAiNotFoundExceptionContract() {
        when(progressQueryService.load(GROUP_ID, USER_ID)).thenThrow(new ProgressNotFoundException());

        assertThrows(AiCoachParticipationNotFoundException.class, () -> service.load(GROUP_ID, USER_ID));
    }
}
