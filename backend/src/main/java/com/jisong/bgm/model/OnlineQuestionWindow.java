package com.jisong.bgm.model;

public record OnlineQuestionWindow(
        String questionId,
        long audioStartMs,
        long audioEndMs,
        long answerStartMs,
        long answerEndMs
) {
}
