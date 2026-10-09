package com.jisong.bgm.dto;

import java.util.List;

public record HostStateResponse(
        EventState state,
        List<HostPlayerView> players,
        List<RoundView> rounds,
        String currentRoundId,
        boolean timelineRunning
) {
    public HostStateResponse {
        players = players == null ? List.of() : List.copyOf(players);
        rounds = rounds == null ? List.of() : List.copyOf(rounds);
    }
}
