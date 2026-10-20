package com.jisong.bgm.dto;

import com.jisong.bgm.model.AppMode;
import jakarta.validation.constraints.NotBlank;

public record JoinRequest(
        @NotBlank(message = "昵称不能为空")
        String nickname,
        AppMode mode,
        String participantId
) {
}
