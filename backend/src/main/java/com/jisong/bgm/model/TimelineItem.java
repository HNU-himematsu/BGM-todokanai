package com.jisong.bgm.model;

public record TimelineItem(
        long atMs,
        ActionType action,
        Question question
) {
}
