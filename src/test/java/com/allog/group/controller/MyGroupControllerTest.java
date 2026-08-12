package com.allog.group.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.FirebaseBearerAuthenticationToken;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.group.dto.MyGroupDetailResponse;
import com.allog.group.dto.MyGroupDetailResponse.Group;
import com.allog.group.dto.MyGroupDetailResponse.Membership;
import com.allog.group.dto.MyGroupDetailResponse.Routine;
import com.allog.group.dto.MyGroupDetailResponse.Schedule;
import com.allog.group.dto.MyGroupsResponse;
import com.allog.group.dto.MyGroupsResponse.Item;
import com.allog.group.service.MyGroupNotFoundException;
import com.allog.group.service.MyGroupQueryService;
import com.allog.routine.domain.ScheduleType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "allog.auth.firebase.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyGroupControllerTest {

    private static final Long USER_ID = 17L;
    private static final String ENDPOINT = "/api/v1/me/groups";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyGroupQueryService queryService;

    @Test
    void usesPrincipalIdentityAndDefaultPaginationWithStableResponseContract() throws Exception {
        when(queryService.readMyGroups(USER_ID, 0, 20)).thenReturn(new MyGroupsResponse(
                List.of(new Item(
                        42L,
                        "아침 물 마시기",
                        GroupVisibility.PUBLIC,
                        RoutineGroupStatus.ACTIVE,
                        "물 마시기",
                        GroupMemberRole.MEMBER,
                        GroupMemberStatus.ACTIVE
                )),
                0,
                20,
                false
        ));

        mockMvc.perform(authenticatedGet()
                        .queryParam("userId", "999999")
                        .header("X-User-Id", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(4)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].*", hasSize(7)))
                .andExpect(jsonPath("$.items[0].groupId").value(42))
                .andExpect(jsonPath("$.items[0].groupName").value("아침 물 마시기"))
                .andExpect(jsonPath("$.items[0].visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.items[0].groupStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].routineName").value("물 마시기"))
                .andExpect(jsonPath("$.items[0].myRole").value("MEMBER"))
                .andExpect(jsonPath("$.items[0].myStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.totalElements").doesNotExist())
                .andExpect(jsonPath("$.totalPages").doesNotExist())
                .andExpect(jsonPath("$.items[0].userId").doesNotExist())
                .andExpect(jsonPath("$.items[0].groupMemberId").doesNotExist());

        verify(queryService).readMyGroups(USER_ID, 0, 20);
        verify(queryService, never()).readMyGroups(999999L, 0, 20);
    }

    @Test
    void forwardsOnlyExplicitPageAndSize() throws Exception {
        when(queryService.readMyGroups(USER_ID, 2, 50))
                .thenReturn(new MyGroupsResponse(List.of(), 2, 50, false));

        mockMvc.perform(authenticatedGet()
                        .queryParam("page", "2")
                        .queryParam("size", "50")
                        .queryParam("sort", "myStatus,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(50));

        verify(queryService).readMyGroups(USER_ID, 2, 50);
    }

    @Test
    void returnsMemberScopedDetailUsingOnlyPrincipalIdentity() throws Exception {
        when(queryService.readMyGroup(USER_ID, 42L)).thenReturn(new MyGroupDetailResponse(
                new Group(42L, "아침 물 마시기", GroupVisibility.PRIVATE,
                        RoutineGroupStatus.ACTIVE, 10, 5),
                new Routine("물 마시기", "매일 물 2L 마시기"),
                new Schedule(
                        ScheduleType.SPECIFIC_DAYS,
                        LocalDate.of(2026, 8, 10),
                        LocalDate.of(2026, 8, 24),
                        LocalTime.of(22, 0),
                        "Asia/Seoul",
                        List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
                ),
                new Membership(GroupMemberRole.MEMBER, GroupMemberStatus.ACTIVE)
        ));

        mockMvc.perform(authenticatedDetailGet(42L)
                        .queryParam("userId", "999999")
                        .header("X-User-Id", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(4)))
                .andExpect(jsonPath("$.group.*", hasSize(6)))
                .andExpect(jsonPath("$.routine.*", hasSize(2)))
                .andExpect(jsonPath("$.schedule.*", hasSize(6)))
                .andExpect(jsonPath("$.membership.*", hasSize(2)))
                .andExpect(jsonPath("$.group.groupId").value(42))
                .andExpect(jsonPath("$.group.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.schedule.specificDays", contains(
                        "MONDAY", "WEDNESDAY", "FRIDAY"
                )))
                .andExpect(jsonPath("$.membership.myStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.membership.groupMemberId").doesNotExist());

        verify(queryService).readMyGroup(USER_ID, 42L);
        verify(queryService, never()).readMyGroup(999999L, 42L);
    }

    @Test
    void missingMyGroupReturnsStatusOnly404() throws Exception {
        when(queryService.readMyGroup(USER_ID, 42L)).thenThrow(new MyGroupNotFoundException());

        mockMvc.perform(authenticatedDetailGet(42L))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveDetailGroupIdReturns400WithoutCallingService(long groupId) throws Exception {
        mockMvc.perform(authenticatedDetailGet(groupId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));

        verifyNoInteractions(queryService);
    }

    @Test
    void unauthenticatedDetailReturns401WithoutCallingService() throws Exception {
        mockMvc.perform(get(ENDPOINT + "/42"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queryService);
    }

    @Test
    void unauthenticatedRequestReturns401WithoutCallingService() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queryService);
    }

    @Test
    void negativePageReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(authenticatedGet().queryParam("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));

        verifyNoInteractions(queryService);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 51})
    void invalidSizeReturns400WithoutCallingService(int size) throws Exception {
        mockMvc.perform(authenticatedGet().queryParam("size", Integer.toString(size)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));

        verifyNoInteractions(queryService);
    }

    private MockHttpServletRequestBuilder authenticatedGet() {
        return get(ENDPOINT).with(authentication(FirebaseBearerAuthenticationToken.authenticated(
                new AllogPrincipal(USER_ID)
        )));
    }

    private MockHttpServletRequestBuilder authenticatedDetailGet(long groupId) {
        return get(ENDPOINT + "/{groupId}", groupId).with(authentication(
                FirebaseBearerAuthenticationToken.authenticated(new AllogPrincipal(USER_ID))
        ));
    }
}
