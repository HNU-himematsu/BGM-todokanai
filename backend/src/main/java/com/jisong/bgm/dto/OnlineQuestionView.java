package com.jisong.bgm.dto;

import com.jisong.bgm.model.Difficulty;
import com.jisong.bgm.model.QuestionType;

import java.util.List;

public record OnlineQuestionView(
        int index,
        String id,
        String prompt,
        QuestionType type,
        Difficulty difficulty,
        List<String> options,
        String author,
        String explanation,
        long audioStartMs,
        long audioEndMs,
        long answerStartMs,
        long answerEndMs
) {
    public OnlineQuestionView {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
