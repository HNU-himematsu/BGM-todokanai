package com.jisong.bgm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;

public record OnlineScoreRecord(
        String eventId,
        String attemptId,
        String participantId,
        String nickname,
        int score,
        int answeredCount,
        Integer correctCount,
        Integer wrongCount,
        int totalQuestions,
        long durationMs,
        String questionSetVersion,
        List<OnlineAnswer> answers,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lockedAt
) {
    public OnlineScoreRecord {
        answers = answers == null ? List.of() : List.copyOf(answers);
    }
}
