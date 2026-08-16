package com.allog.group.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinGroupByInviteRequest(@NotBlank @Size(max = 32) String code) {
}
