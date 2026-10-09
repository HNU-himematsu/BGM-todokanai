package com.jisong.bgm.dto;

import jakarta.validation.constraints.NotBlank;

public record SelectRoundRequest(
        @NotBlank(message = "轮次标识不能为空")
        String roundId
) {
}
