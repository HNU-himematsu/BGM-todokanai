package com.jisong.bgm.model;

public enum Difficulty {
    EASY(2),
    MEDIUM(3),
    HARD(5);

    private final int points;

    Difficulty(int points) {
        this.points = points;
    }

    public int points() {
        return points;
    }
}
