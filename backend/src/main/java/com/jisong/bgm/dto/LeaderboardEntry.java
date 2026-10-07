package com.jisong.bgm.dto;

public record LeaderboardEntry(
        int rank,
        String playerId,
        String nickname,
        int score,
        Long durationMs
) {
    public LeaderboardEntry {
        if (durationMs == null) {
            durationMs = 0L;
        }
    }
}
