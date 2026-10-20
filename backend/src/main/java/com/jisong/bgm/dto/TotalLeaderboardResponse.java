package com.jisong.bgm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;

public record TotalLeaderboardResponse(
        String eventId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant generatedAt,
        List<TotalLeaderboardEntry> entries
) {
    public TotalLeaderboardResponse {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
