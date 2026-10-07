package com.jisong.bgm.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SubmitAnswerRequest(
        @NotBlank String playerId,
        @NotBlank String questionId,
        @Min(0) @Max(3) int optionIndex
) {
}
