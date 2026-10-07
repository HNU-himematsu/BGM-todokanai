package com.jisong.bgm.dto;

import com.jisong.bgm.model.ActivityStatus;

import java.util.List;

public record EventState(
        String eventId,
        String eventName,
        ActivityStatus status,
        int currentQuestionIndex,
        int totalQuestions,
        Long deadlineEpochMs,
        boolean answerRevealed,
        boolean canAnswer,
        QuestionView question,
        boolean hasAnswered,
        Integer selectedOption,
        int playerScore,
        long playerDurationMs,
        int playerRank,
        int playerCount,
        int answeredCount,
        boolean autoMode,
        List<LeaderboardEntry> leaderboard
) {
}
