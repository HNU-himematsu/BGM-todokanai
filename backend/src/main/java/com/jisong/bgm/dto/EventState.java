package com.jisong.bgm.dto;

import com.jisong.bgm.model.ActivityStatus;

import java.util.List;

public record EventState(
        String eventId,
        String eventName,
        ActivityStatus status,
        int currentQuestionIndex,
        int totalQuestions,
        Long questionAudioStartEpochMs,
        Long questionAudioEndEpochMs,
        boolean answerRevealed,
        boolean timelineRunning,
        Long audioPositionMs,
        boolean canAnswer,
        QuestionView question,
        boolean hasAnswered,
        Integer selectedOption,
        int playerScore,
        long playerDurationMs,
        int playerRank,
        int playerCount,
        int answeredCount,
        List<LeaderboardEntry> leaderboard,
        long revision
) {
}
