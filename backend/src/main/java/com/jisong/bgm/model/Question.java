package com.jisong.bgm.model;

import java.util.List;

public record Question(
        String id,
        String audioCode,
        String prompt,
        QuestionType type,
        Difficulty difficulty,
        List<String> options,
        int correctOption,
        String workName,
        String explanation
) {
    public Question {
        options = List.copyOf(options);
        if (audioCode == null || audioCode.isBlank()) {
            audioCode = id;
        }
    }
}
