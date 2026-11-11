package com.jisong.bgm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record NicknameConflict(
        String eventId,
        String nickname,
        String nicknameKey,
        String participantId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant requestedAt
) {
}
