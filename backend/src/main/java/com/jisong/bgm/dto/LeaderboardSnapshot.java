package com.jisong.bgm.dto;

import java.time.Instant;
import java.util.List;

public record LeaderboardSnapshot(
        String eventId,
        String eventName,
        Instant finishedAt,
        List<LeaderboardEntry> entries
) {
    public LeaderboardSnapshot {
        entries = List.copyOf(entries);
    }
}
