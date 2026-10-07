package com.jisong.bgm.model;

import java.time.Instant;

public class Player {
    private final String id;
    private final String nickname;
    private final Instant joinedAt;
    private boolean canAnswer;
    private int score;
    private long totalDurationMs;

    public Player(String id, String nickname, boolean canAnswer) {
        this.id = id;
        this.nickname = nickname;
        this.joinedAt = Instant.now();
        this.canAnswer = canAnswer;
        this.score = 0;
        this.totalDurationMs = 0L;
    }

    public String getId() {
        return id;
    }

    public String getNickname() {
        return nickname;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public boolean canAnswer() {
        return canAnswer;
    }

    public void setCanAnswer(boolean canAnswer) {
        this.canAnswer = canAnswer;
    }

    public int getScore() {
        return score;
    }

    public void addScore(int points) {
        score += points;
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    public void addDuration(long durationMs) {
        totalDurationMs += durationMs;
    }

    public void resetScore() {
        score = 0;
        totalDurationMs = 0L;
    }
}
