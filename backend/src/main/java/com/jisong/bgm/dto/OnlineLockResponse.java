package com.jisong.bgm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record OnlineLockResponse(
        String attemptId,
        String participantId,
        String nickname,
        int score,
        int answeredCount,
        int totalQuestions,
        long durationMs,
        int onlineRank,
        int totalRank,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lockedAt
) {
}
