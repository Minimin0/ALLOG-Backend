package com.allog.group.dto;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import org.springframework.data.domain.Slice;

import java.util.List;
import java.util.Objects;

public record MyGroupsResponse(
        List<Item> items,
        int page,
        int size,
        boolean hasNext
) {

    public MyGroupsResponse {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }

    public static MyGroupsResponse from(Slice<GroupMember> memberships) {
        return new MyGroupsResponse(
                memberships.getContent().stream().map(Item::from).toList(),
                memberships.getNumber(),
                memberships.getSize(),
                memberships.hasNext()
        );
    }

    public record Item(
            Long groupId,
            String groupName,
            GroupVisibility visibility,
            RoutineGroupStatus groupStatus,
            String routineName,
            GroupMemberRole myRole,
            GroupMemberStatus myStatus
    ) {

        private static Item from(GroupMember membership) {
            RoutineGroup group = membership.getRoutineGroup();
            return new Item(
                    group.getId(),
                    group.getName(),
                    group.getVisibility(),
                    group.getStatus(),
                    group.getRoutineDefinition().getName(),
                    membership.getRole(),
                    membership.getStatus()
            );
        }
    }
}
