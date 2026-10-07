package com.jisong.bgm.dto;

import com.jisong.bgm.model.ActivityStatus;

import java.util.List;

public record EventState(
        String eventId,
        String eventName,
        ActivityStatus status,
        int currentQuestionNo,
        int totalQuestions,
        Long deadline,
        boolean answerRevealed,
        boolean canAnswer,
        QuestionView currentQuestion,
        boolean hasAnswered,
        Integer selectedOption,
        int playerScore,
        long playerDurationMs,
        int playerRank,
        int onlinePlayers,
        boolean autoMode,
        List<LeaderboardEntry> leaderboard
) {
}
