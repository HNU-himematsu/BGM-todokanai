package com.jisong.bgm.dto;

public record HostPlayerView(
        String playerId,
        String nickname,
        boolean answered
) {
}
