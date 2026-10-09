package com.jisong.bgm.dto;

import com.jisong.bgm.model.Difficulty;
import com.jisong.bgm.model.QuestionType;

import java.util.List;

public record QuestionView(
        String id,
        String prompt,
        Difficulty difficulty,
        QuestionType type,
        List<String> options,
        String workName,
        String analysis,
        Integer answerIndex
) {
}
