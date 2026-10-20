package com.jisong.bgm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;

public record OnlineLeaderboardResponse(
        String eventId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant generatedAt,
        List<OnlineLeaderboardEntry> entries
) {
    public OnlineLeaderboardResponse {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
