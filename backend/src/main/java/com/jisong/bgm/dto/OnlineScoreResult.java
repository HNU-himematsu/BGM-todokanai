package com.jisong.bgm.dto;

public record OnlineScoreResult(
        int score,
        int answeredCount,
        int totalQuestions,
        long durationMs,
        String questionSetVersion
) {
}
