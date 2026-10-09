package com.jisong.bgm.dto;

public record RoundView(
        String id,
        String name,
        long startMs,
        long endMs,
        int totalQuestions
) {
}
