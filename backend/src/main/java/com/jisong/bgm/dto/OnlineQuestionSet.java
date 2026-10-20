package com.jisong.bgm.dto;

import java.util.List;

public record OnlineQuestionSet(
        String eventId,
        String eventName,
        String audioUrl,
        String questionSetVersion,
        long audioStartMs,
        long audioEndMs,
        int totalQuestions,
        List<OnlineQuestionView> questions
) {
    public OnlineQuestionSet {
        questions = questions == null ? List.of() : List.copyOf(questions);
    }
}
