package com.jisong.bgm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;

public record TotalLeaderboardEntry(
        int rank,
        String participantId,
        String nickname,
        int totalScore,
        int offlineScore,
        int onlineScore,
        long totalDurationMs,
        int offlineRoundCount,
        boolean onlineLocked,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastActivityAt,
        List<OfflineContribution> offlineContributions,
        OnlineContribution onlineContribution
) {
    public TotalLeaderboardEntry {
        offlineContributions = offlineContributions == null ? List.of() : List.copyOf(offlineContributions);
    }
}
