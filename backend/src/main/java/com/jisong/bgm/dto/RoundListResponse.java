package com.jisong.bgm.dto;

import java.util.List;

public record RoundListResponse(
        List<RoundView> rounds,
        String currentRoundId
) {
    public RoundListResponse {
        rounds = rounds == null ? List.of() : List.copyOf(rounds);
    }
}
