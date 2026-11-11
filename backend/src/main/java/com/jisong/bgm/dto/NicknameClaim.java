package com.jisong.bgm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record NicknameClaim(
        String claimId,
        String eventId,
        String nicknameKey,
        String nickname,
        String participantId,
        String source,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant claimedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant updatedAt
) {
}
