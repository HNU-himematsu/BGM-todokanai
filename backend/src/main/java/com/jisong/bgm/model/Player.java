package com.jisong.bgm.model;

import java.time.Instant;

public class Player {
    private final String id;
    private final String participantId;
    private final String nickname;
    private final Instant joinedAt;
    private int score;
    private long totalDurationMs;

    public Player(String id, String participantId, String nickname) {
        this.id = id;
        this.participantId = participantId;
        this.nickname = nickname;
        this.joinedAt = Instant.now();
        this.score = 0;
        this.totalDurationMs = 0L;
    }

    public String getId() {
        return id;
    }

    public String getParticipantId() {
        return participantId;
    }

    public String getNickname() {
        return nickname;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public long getJoinedAtEpochMs() {
        return joinedAt.toEpochMilli();
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
