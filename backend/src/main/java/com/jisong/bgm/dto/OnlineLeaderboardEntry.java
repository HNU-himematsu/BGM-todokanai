package com.jisong.bgm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record OnlineLeaderboardEntry(
        int rank,
        String participantId,
        String nickname,
        int score,
        int answeredCount,
        int totalQuestions,
        long durationMs,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lockedAt
) {
}
