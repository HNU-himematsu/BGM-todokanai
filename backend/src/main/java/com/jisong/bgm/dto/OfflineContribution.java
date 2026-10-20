package com.jisong.bgm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record OfflineContribution(
        String roundId,
        String roundName,
        int score,
        long durationMs,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant finishedAt
) {
}
