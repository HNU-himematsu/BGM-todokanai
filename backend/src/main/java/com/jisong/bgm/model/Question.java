package com.jisong.bgm.model;

import java.util.List;

public record Question(
        String id,
        String audioCode,
        String prompt,
        QuestionType type,
        Difficulty difficulty,
        List<String> options,
        Integer correctOption,
        String workName,
        String explanation,
        String author,
        Long audioStartMs,
        Long audioEndMs
) {
    public Question {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
