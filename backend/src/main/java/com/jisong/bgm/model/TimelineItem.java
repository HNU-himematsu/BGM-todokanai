package com.jisong.bgm.model;

public record TimelineItem(
        long atMs,
        ActionType action,
        String questionId,
        Long audioStartMs,
        Long audioEndMs,
        Question question
) {
    public TimelineItem withQuestion(Question resolvedQuestion) {
        return new TimelineItem(atMs, action, questionId, audioStartMs, audioEndMs, resolvedQuestion);
    }
}
