package com.jisong.bgm.dto;

import com.jisong.bgm.model.Difficulty;
import com.jisong.bgm.model.QuestionType;

import java.util.List;

public record QuestionView(
        String id,
        String audioCode,
        String prompt,
        QuestionType type,
        Difficulty difficulty,
        List<String> options,
        String workName,
        String explanation,
        Integer correctOption
) {
}
