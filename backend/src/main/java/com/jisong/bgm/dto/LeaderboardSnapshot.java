package com.jisong.bgm.dto;

import java.time.Instant;
import java.util.List;

public record LeaderboardSnapshot(
        String eventId,
        String eventName,
        String roundId,
        String roundName,
        Instant finishedAt,
        List<LeaderboardEntry> entries
) {
    public LeaderboardSnapshot {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
