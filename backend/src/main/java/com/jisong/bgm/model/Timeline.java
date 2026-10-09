package com.jisong.bgm.model;

import java.util.List;

public record Timeline(
        String eventId,
        String eventName,
        String audioFile,
        List<Round> rounds
) {
    public Timeline {
        rounds = rounds == null ? List.of() : List.copyOf(rounds);
    }
}
