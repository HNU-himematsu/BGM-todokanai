package com.jisong.bgm.dto;

import java.util.List;

public interface OnlineAttemptPayload {
    String participantId();

    String nickname();

    String attemptId();

    String questionSetVersion();

    Long durationMs();

    List<OnlineAnswer> answers();
}
