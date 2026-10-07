package com.jisong.bgm.dto;

import jakarta.validation.constraints.NotBlank;

public record HostLoginRequest(@NotBlank(message = "请输入主持人密码") String password) {
}
