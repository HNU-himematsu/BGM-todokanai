package com.jisong.bgm.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record OnlineLockRequest(
        @NotBlank(message = "参与者标识不能为空")
        String participantId,
        @NotBlank(message = "昵称不能为空")
        String nickname,
        @NotBlank(message = "答题尝试标识不能为空")
        String attemptId,
        @NotBlank(message = "题集版本不能为空")
        String questionSetVersion,
        @NotNull(message = "作答耗时不能为空")
        @Min(value = 0, message = "作答耗时不能为负数")
        Long durationMs,
        @NotNull(message = "答案数组不能为空")
        List<@Valid OnlineAnswer> answers
) implements OnlineAttemptPayload {
}
