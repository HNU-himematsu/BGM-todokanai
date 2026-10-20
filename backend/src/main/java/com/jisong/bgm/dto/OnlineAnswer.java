package com.jisong.bgm.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OnlineAnswer(
        @NotBlank(message = "题目编号不能为空") String questionId,
        @NotNull(message = "选项下标不能为空") @Min(0) @Max(3) Integer optionIndex
) {
}
