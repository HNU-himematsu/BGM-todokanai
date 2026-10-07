package com.jisong.bgm.controller;

import com.jisong.bgm.dto.ApiResponse;
import com.jisong.bgm.dto.EventState;
import com.jisong.bgm.dto.JoinRequest;
import com.jisong.bgm.dto.JoinResponse;
import com.jisong.bgm.dto.LeaderboardEntry;
import com.jisong.bgm.dto.LeaderboardSnapshot;
import com.jisong.bgm.dto.SubmitAnswerRequest;
import com.jisong.bgm.service.ActivityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class EventController {
    private final ActivityService activityService;

    public EventController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.ok("bgm-guess-backend is running");
    }

    @GetMapping("/events/{eventId}")
    public ApiResponse<ActivityService.ActivitySummary> getEvent(@PathVariable String eventId) {
        return ApiResponse.ok(activityService.getActivitySummary(eventId));
    }

    @PostMapping("/events/{eventId}/join")
    public ApiResponse<JoinResponse> join(
            @PathVariable String eventId,
            @Valid @RequestBody JoinRequest request
    ) {
        return ApiResponse.ok(activityService.join(eventId, request.nickname()));
    }

    @GetMapping("/events/{eventId}/state")
    public ApiResponse<EventState> state(
            @PathVariable String eventId,
            @RequestParam(required = false) String playerId
    ) {
        return ApiResponse.ok(activityService.getState(eventId, playerId));
    }

    @PostMapping("/events/{eventId}/answers")
    public ApiResponse<EventState> answer(
            @PathVariable String eventId,
            @Valid @RequestBody SubmitAnswerRequest request
    ) {
        return ApiResponse.ok(activityService.submitAnswer(eventId, request));
    }

    @GetMapping("/events/{eventId}/leaderboard")
    public ApiResponse<List<LeaderboardEntry>> leaderboard(@PathVariable String eventId) {
        return ApiResponse.ok(activityService.getLeaderboard(eventId));
    }

    @GetMapping("/events/{eventId}/leaderboards/history")
    public ApiResponse<List<LeaderboardSnapshot>> leaderboardHistory(@PathVariable String eventId) {
        return ApiResponse.ok(activityService.getLeaderboardHistory(eventId));
    }

    @GetMapping(value = {"/events/{eventId}/leaderboard/summary-txt", "/events/{eventId}/leaderboards/summary-txt"}, produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> leaderboardSummaryTxt(@PathVariable String eventId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"leaderboard_summary.txt\"")
                .body(activityService.getLeaderboardSummaryTxt());
    }
}
