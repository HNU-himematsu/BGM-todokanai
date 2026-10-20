package com.jisong.bgm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record LeaderboardEntry(
        int rank,
        String participantId,
        String playerId,
        String nickname,
        int score,
        long durationMs,
        int answeredCount,
        int totalQuestions,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant joinedAt
) {
    public LeaderboardEntry {
        durationMs = Math.max(0L, durationMs);
        answeredCount = Math.max(0, answeredCount);
        totalQuestions = Math.max(0, totalQuestions);
    }
}
