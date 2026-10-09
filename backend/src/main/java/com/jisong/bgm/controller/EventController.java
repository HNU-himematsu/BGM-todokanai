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
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api")
public class EventController {
    private static final long MAX_RANGE_CHUNK_BYTES = 1024 * 1024L;

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

    @GetMapping("/events/{eventId}/audio")
    public ResponseEntity<?> audio(
            @PathVariable String eventId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader
    ) throws IOException {
        Path audioPath = activityService.audioPath(eventId);
        if (audioPath == null || !Files.isReadable(audioPath)) {
            throw new IllegalStateException("音频文件不可读：" + audioPath);
        }
        FileSystemResource resource = new FileSystemResource(audioPath);
        long contentLength = resource.contentLength();
        MediaType mediaType = MediaTypeFactory.getMediaType(audioPath.getFileName().toString())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentLength(contentLength)
                    .body(resource);
        }
        List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
        if (ranges.isEmpty()) {
            throw new IllegalArgumentException("Range 请求不合法");
        }
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(contentLength);
        long end = range.getRangeEnd(contentLength);
        long rangeLength = Math.min(Math.min(contentLength - start, end - start + 1), MAX_RANGE_CHUNK_BYTES);
        byte[] chunk = new byte[(int) rangeLength];
        try (RandomAccessFile file = new RandomAccessFile(audioPath.toFile(), "r")) {
            file.seek(start);
            file.readFully(chunk);
        }
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE,
                        "bytes " + start + "-" + (start + rangeLength - 1) + "/" + contentLength)
                .contentLength(rangeLength)
                .body(chunk);
    }
}
