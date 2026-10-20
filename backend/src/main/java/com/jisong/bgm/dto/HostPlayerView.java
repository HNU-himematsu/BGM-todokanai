package com.jisong.bgm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record HostPlayerView(
        String playerId,
        String participantId,
        String nickname,
        boolean answered,
        int score,
        long durationMs,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant joinedAt
) {
}
