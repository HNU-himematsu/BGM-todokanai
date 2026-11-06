package com.jisong.bgm.controller;

import com.jisong.bgm.dto.ApiResponse;
import com.jisong.bgm.dto.EventState;
import com.jisong.bgm.dto.JoinRequest;
import com.jisong.bgm.dto.JoinResponse;
import com.jisong.bgm.dto.LeaderboardEntry;
import com.jisong.bgm.dto.LeaderboardSnapshot;
import com.jisong.bgm.dto.OnlineLeaderboardResponse;
import com.jisong.bgm.dto.OnlineLockRequest;
import com.jisong.bgm.dto.OnlineLockResponse;
import com.jisong.bgm.dto.OnlinePreviewRequest;
import com.jisong.bgm.dto.OnlineQuestionSet;
import com.jisong.bgm.dto.OnlineScoreResult;
import com.jisong.bgm.dto.OnlineStatusResponse;
import com.jisong.bgm.dto.SubmitAnswerRequest;
import com.jisong.bgm.dto.TotalLeaderboardResponse;
import com.jisong.bgm.service.ActivityService;
import com.jisong.bgm.service.OnlineAttemptService;
import com.jisong.bgm.service.RangeNotSatisfiableException;
import com.jisong.bgm.service.ResourceNotFoundException;
import jakarta.validation.Valid;
import org.springframework.core.io.AbstractResource;
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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api")
public class EventController {
    private final ActivityService activityService;
    private final OnlineAttemptService onlineAttemptService;

    public EventController(ActivityService activityService, OnlineAttemptService onlineAttemptService) {
        this.activityService = activityService;
        this.onlineAttemptService = onlineAttemptService;
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
        return ApiResponse.ok(activityService.join(eventId, request));
    }

    @GetMapping("/events/{eventId}/online/questions")
    public ApiResponse<OnlineQuestionSet> onlineQuestions(@PathVariable String eventId) {
        return ApiResponse.ok(onlineAttemptService.questionSet(eventId));
    }

    @GetMapping("/events/{eventId}/online/status")
    public ApiResponse<OnlineStatusResponse> onlineStatus(
            @PathVariable String eventId,
            @RequestParam String participantId
    ) {
        return ApiResponse.ok(onlineAttemptService.status(eventId, participantId));
    }

    @PostMapping("/events/{eventId}/online/preview")
    public ApiResponse<OnlineScoreResult> onlinePreview(
            @PathVariable String eventId,
            @Valid @RequestBody OnlinePreviewRequest request
    ) {
        return ApiResponse.ok(onlineAttemptService.preview(eventId, request));
    }

    @PostMapping("/events/{eventId}/online/lock")
    public ApiResponse<OnlineLockResponse> onlineLock(
            @PathVariable String eventId,
            @Valid @RequestBody OnlineLockRequest request
    ) {
        return ApiResponse.ok(onlineAttemptService.lock(eventId, request));
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

    @GetMapping("/events/{eventId}/leaderboards/online")
    public ApiResponse<OnlineLeaderboardResponse> onlineLeaderboard(@PathVariable String eventId) {
        return ApiResponse.ok(onlineAttemptService.onlineLeaderboard(eventId));
    }

    @GetMapping("/events/{eventId}/leaderboards/total")
    public ApiResponse<TotalLeaderboardResponse> totalLeaderboard(@PathVariable String eventId) {
        return ApiResponse.ok(onlineAttemptService.totalLeaderboard(eventId));
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
            @RequestParam(value = "mode", required = false, defaultValue = "offline") String mode,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader
    ) throws IOException {
        boolean online = "online".equals(mode);
        String audioUrl = online
                ? activityService.onlineAudioUrl(eventId)
                : activityService.audioUrl(eventId);
        if (audioUrl != null
                && (audioUrl.startsWith("http://") || audioUrl.startsWith("https://"))) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(audioUrl))
                    .build();
        }
        Path audioPath = online
                ? activityService.onlineAudioPath(eventId)
                : activityService.audioPath(eventId);
        if (audioPath == null || !Files.isReadable(audioPath)) {
            throw new ResourceNotFoundException("音频文件不可读：" + audioPath);
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
        long start;
        long end;
        try {
            start = range.getRangeStart(contentLength);
            end = range.getRangeEnd(contentLength);
        } catch (IllegalArgumentException exception) {
            throw new RangeNotSatisfiableException(contentLength, "Range 超出音频文件长度");
        }
        if (contentLength <= 0 || start < 0 || start >= contentLength || end < start) {
            throw new RangeNotSatisfiableException(contentLength, "Range 超出音频文件长度");
        }
        long rangeLength = Math.min(contentLength - start, end - start + 1);
        FileRangeResource body = new FileRangeResource(audioPath, start, rangeLength);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE,
                        "bytes " + start + "-" + (start + rangeLength - 1) + "/" + contentLength)
                .contentLength(rangeLength)
                .body(body);
    }

    private static final class FileRangeResource extends AbstractResource {
        private final Path path;
        private final long start;
        private final long length;

        private FileRangeResource(Path path, long start, long length) {
            this.path = path;
            this.start = start;
            this.length = length;
        }

        @Override
        public String getDescription() {
            return "音频区间 [" + start + ", " + (start + length) + ") · " + path;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            InputStream input = Files.newInputStream(path);
            try {
                input.skipNBytes(start);
                return new BoundedInputStream(input, length);
            } catch (IOException exception) {
                input.close();
                throw exception;
            }
        }

        @Override
        public long contentLength() {
            return length;
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        private BoundedInputStream(InputStream input, long limit) {
            super(input);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public long skip(long count) throws IOException {
            long skipped = super.skip(Math.min(count, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }
    }
}
