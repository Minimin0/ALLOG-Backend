package com.allog.progress.service;

import com.allog.group.domain.GroupMemberStatus;
import com.allog.progress.domain.AuthoritativeProgressFacts;
import com.allog.progress.dto.ProgressResponse;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class ProgressReadService {

    private final AuthoritativeProgressQueryService queryService;

    public ProgressReadService(AuthoritativeProgressQueryService queryService) {
        this.queryService = Objects.requireNonNull(queryService, "queryService must not be null");
    }

    public ProgressResponse read(Long groupId, Long currentUserId) {
        AuthoritativeProgressFacts facts = queryService.load(groupId, currentUserId);
        if (facts.participationStatus() == GroupMemberStatus.LEFT
                || facts.participationStatus() == GroupMemberStatus.REMOVED) {
            throw new ProgressNotFoundException();
        }
        return ProgressResponse.from(facts);
    }
}
