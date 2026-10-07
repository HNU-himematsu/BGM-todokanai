package com.jisong.bgm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinRequest(
        @NotBlank(message = "昵称不能为空")
        @Size(max = 20, message = "昵称不能超过20个字符")
        String nickname
) {
}
