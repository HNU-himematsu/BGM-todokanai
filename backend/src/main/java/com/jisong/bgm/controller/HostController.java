package com.jisong.bgm.controller;

import com.jisong.bgm.dto.ApiResponse;
import com.jisong.bgm.dto.EventState;
import com.jisong.bgm.dto.HostLoginRequest;
import com.jisong.bgm.dto.HostLoginResponse;
import com.jisong.bgm.dto.HostStateResponse;
import com.jisong.bgm.dto.RoundListResponse;
import com.jisong.bgm.dto.SelectRoundRequest;
import com.jisong.bgm.service.ActivityService;
import com.jisong.bgm.service.HostAuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HostController {
    private final ActivityService activityService;
    private final HostAuthService hostAuthService;

    public HostController(ActivityService activityService, HostAuthService hostAuthService) {
        this.activityService = activityService;
        this.hostAuthService = hostAuthService;
    }

    @PostMapping("/host/login")
    public ApiResponse<HostLoginResponse> login(@Valid @RequestBody HostLoginRequest request) {
        return ApiResponse.ok(new HostLoginResponse(hostAuthService.login(request.password())));
    }

    @PostMapping("/host/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.logout(token);
        return ApiResponse.ok();
    }

    @GetMapping("/events/{eventId}/host/rounds")
    public ApiResponse<RoundListResponse> rounds(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.listRounds(eventId));
    }

    @PostMapping("/events/{eventId}/host/round")
    public ApiResponse<EventState> selectRound(
            @PathVariable String eventId,
            @Valid @RequestBody SelectRoundRequest request,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.selectRound(eventId, request.roundId()));
    }

    @GetMapping("/events/{eventId}/host/state")
    public ApiResponse<HostStateResponse> state(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.getHostState(eventId));
    }

    @PostMapping("/events/{eventId}/host/start")
    public ApiResponse<EventState> start(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.startTimeline(eventId));
    }

    @PostMapping("/events/{eventId}/host/pause")
    public ApiResponse<EventState> pause(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.pauseTimeline(eventId));
    }

    @PostMapping("/events/{eventId}/host/resume")
    public ApiResponse<EventState> resume(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.resumeTimeline(eventId));
    }

    @PostMapping("/events/{eventId}/host/lock")
    public ApiResponse<EventState> lock(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.lockQuestion(eventId));
    }

    @PostMapping("/events/{eventId}/host/reveal")
    public ApiResponse<EventState> reveal(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.revealAnswer(eventId));
    }

    @PostMapping("/events/{eventId}/host/next")
    public ApiResponse<EventState> next(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.nextQuestion(eventId));
    }

    @PostMapping("/events/{eventId}/host/prev")
    public ApiResponse<EventState> previous(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.previousQuestion(eventId));
    }

    @PostMapping("/events/{eventId}/host/finish")
    public ApiResponse<EventState> finish(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.finishCurrentRound(eventId));
    }

    @PostMapping("/events/{eventId}/host/reset")
    public ApiResponse<EventState> reset(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.reset(eventId));
    }
}
