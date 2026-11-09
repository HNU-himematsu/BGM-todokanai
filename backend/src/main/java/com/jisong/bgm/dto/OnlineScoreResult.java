package com.jisong.bgm.dto;

public record OnlineScoreResult(
        int score,
        int answeredCount,
        int correctCount,
        int wrongCount,
        int totalQuestions,
        long durationMs,
        String questionSetVersion
) {
}
