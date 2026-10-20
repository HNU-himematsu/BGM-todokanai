package com.jisong.bgm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.jisong.bgm.model.OnlineLockStatus;

import java.time.Instant;

public record OnlineStatusResponse(
        String participantId,
        OnlineLockStatus status,
        Integer score,
        Integer answeredCount,
        Integer totalQuestions,
        Long durationMs,
        Integer onlineRank,
        Integer totalRank,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lockedAt
) {
}
