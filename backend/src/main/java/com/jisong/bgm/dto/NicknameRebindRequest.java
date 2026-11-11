package com.jisong.bgm.dto;

import jakarta.validation.constraints.NotBlank;

public record NicknameRebindRequest(
        @NotBlank(message = "参与者标识不能为空")
        String participantId
) {
}
