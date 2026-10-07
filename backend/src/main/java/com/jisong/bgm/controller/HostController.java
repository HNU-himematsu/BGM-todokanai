package com.jisong.bgm.controller;

import com.jisong.bgm.dto.ApiResponse;
import com.jisong.bgm.dto.EventState;
import com.jisong.bgm.dto.HostLoginRequest;
import com.jisong.bgm.dto.HostLoginResponse;
import com.jisong.bgm.service.ActivityService;
import com.jisong.bgm.service.HostAuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public ApiResponse<Void> logout(@RequestHeader(value = "X-Host-Token", required = false) String token) {
        hostAuthService.logout(token);
        return ApiResponse.ok();
    }

    @PostMapping("/events/{eventId}/host/start")
    public ApiResponse<EventState> start(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.startQuestion(eventId));
    }

    @PostMapping("/events/{eventId}/host/end")
    public ApiResponse<EventState> end(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.endAnswer(eventId));
    }

    @PostMapping("/events/{eventId}/host/reveal")
    public ApiResponse<EventState> reveal(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.reveal(eventId));
    }

    @PostMapping("/events/{eventId}/host/next")
    public ApiResponse<EventState> next(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.nextQuestion(eventId));
    }

    @PostMapping("/events/{eventId}/host/reset")
    public ApiResponse<EventState> reset(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.reset(eventId));
    }

    @PostMapping("/events/{eventId}/host/pause")
    public ApiResponse<EventState> pause(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.pauseAuto(eventId));
    }

    @PostMapping("/events/{eventId}/host/resume")
    public ApiResponse<EventState> resume(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.resumeAuto(eventId));
    }

    @PostMapping("/events/{eventId}/host/toggle-auto")
    public ApiResponse<EventState> toggleAuto(
            @PathVariable String eventId,
            @RequestParam boolean enabled,
            @RequestHeader(value = "X-Host-Token", required = false) String token
    ) {
        hostAuthService.requireValid(token);
        return ApiResponse.ok(activityService.setAutoMode(eventId, enabled));
    }
}
