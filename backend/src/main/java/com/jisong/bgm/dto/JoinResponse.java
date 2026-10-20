package com.jisong.bgm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.jisong.bgm.model.AppMode;

import java.time.Instant;

public record JoinResponse(
        String eventId,
        String eventName,
        AppMode mode,
        String participantId,
        String playerId,
        String nickname,
        boolean onlineLocked,
        Integer onlineScore,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lockedAt
) {
}
