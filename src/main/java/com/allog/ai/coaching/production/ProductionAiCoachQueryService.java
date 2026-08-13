package com.allog.ai.coaching.production;

import com.allog.progress.domain.AuthoritativeProgressFacts;
import com.allog.progress.service.AuthoritativeProgressQueryService;
import com.allog.progress.service.ProgressNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class ProductionAiCoachQueryService {

    private final AuthoritativeProgressQueryService progressQueryService;

    public ProductionAiCoachQueryService(AuthoritativeProgressQueryService progressQueryService) {
        this.progressQueryService = Objects.requireNonNull(
                progressQueryService,
                "progressQueryService must not be null"
        );
    }

    public ProductionAiCoachFacts load(Long groupId, Long currentUserId) {
        try {
            AuthoritativeProgressFacts facts = progressQueryService.load(groupId, currentUserId);
            return new ProductionAiCoachFacts(
                    facts.groupName(),
                    facts.participationStatus(),
                    facts.personalProgress(),
                    facts.groupProgress()
            );
        } catch (ProgressNotFoundException ignored) {
            throw new AiCoachParticipationNotFoundException(groupId, currentUserId);
        }
    }
}
