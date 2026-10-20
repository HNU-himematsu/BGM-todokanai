package com.jisong.bgm.service;

import com.jisong.bgm.dto.OnlineAnswer;
import com.jisong.bgm.dto.OnlineAttemptPayload;
import com.jisong.bgm.dto.OnlineLeaderboardEntry;
import com.jisong.bgm.dto.OnlineLeaderboardResponse;
import com.jisong.bgm.dto.OnlineLockResponse;
import com.jisong.bgm.dto.OnlineQuestionSet;
import com.jisong.bgm.dto.OnlineQuestionView;
import com.jisong.bgm.dto.OnlineScoreRecord;
import com.jisong.bgm.dto.OnlineScoreResult;
import com.jisong.bgm.dto.OnlineStatusResponse;
import com.jisong.bgm.dto.TotalLeaderboardEntry;
import com.jisong.bgm.dto.TotalLeaderboardResponse;
import com.jisong.bgm.model.OnlineLockStatus;
import com.jisong.bgm.model.OnlineQuestionWindow;
import com.jisong.bgm.model.Question;
import com.jisong.bgm.model.Timeline;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OnlineAttemptService {
    private static final int POINTS_PER_CORRECT = 10;

    private final TimelineStore timelineStore;
    private final LeaderboardStore leaderboardStore;
    private final SimpMessagingTemplate messagingTemplate;

    public OnlineAttemptService(
            TimelineStore timelineStore,
            LeaderboardStore leaderboardStore,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.timelineStore = timelineStore;
        this.leaderboardStore = leaderboardStore;
        this.messagingTemplate = messagingTemplate;
    }

    public OnlineQuestionSet questionSet(String eventId) {
        Timeline timeline = requireEvent(eventId);
        timelineStore.requireAudioReadable();
        List<OnlineQuestionView> questions = new ArrayList<>();
        for (int index = 0; index < timelineStore.onlineQuestions().size(); index++) {
            OnlineQuestionWindow window = timelineStore.onlineQuestions().get(index);
            Question question = timelineStore.question(window.questionId());
            questions.add(new OnlineQuestionView(
                    index,
                    question.id(),
                    question.prompt(),
                    question.type(),
                    question.difficulty(),
                    question.options(),
                    window.audioStartMs(),
                    window.audioEndMs(),
                    window.answerStartMs(),
                    window.answerEndMs()));
        }
        return new OnlineQuestionSet(
                timeline.eventId(),
                timeline.eventName(),
                "/api/events/" + eventId + "/audio",
                timelineStore.questionSetVersion(),
                timelineStore.onlineQuestions().get(0).audioStartMs(),
                timelineStore.onlineQuestions().get(timelineStore.onlineQuestions().size() - 1).audioEndMs(),
                questions.size(),
                questions);
    }

    public OnlineScoreResult preview(String eventId, OnlineAttemptPayload payload) {
        Submission submission = validate(eventId, payload);
        return score(submission);
    }

    public synchronized OnlineLockResponse lock(String eventId, OnlineAttemptPayload payload) {
        Timeline timeline = requireEvent(eventId);
        if (payload == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String participantId = requiredText(payload.participantId(), "参与者标识", 64);
        String attemptId = requiredText(payload.attemptId(), "答题尝试标识", 64);
        OnlineScoreRecord existing = leaderboardStore.onlineRecord(
                timeline.eventId(), participantId);
        if (existing != null) {
            if (attemptId.equals(existing.attemptId())) {
                return lockResponse(existing);
            }
            throw new ConflictException("该参与者已经以其他答题尝试锁定成绩");
        }

        Submission submission = validate(eventId, payload);
        OnlineScoreResult result = score(submission);
        OnlineScoreRecord record = new OnlineScoreRecord(
                submission.eventId,
                submission.attemptId,
                submission.participantId,
                submission.nickname,
                result.score(),
                result.answeredCount(),
                result.totalQuestions(),
                result.durationMs(),
                result.questionSetVersion(),
                normalizedAnswers(submission),
                Instant.now());
        OnlineScoreRecord saved = leaderboardStore.saveOnline(record);
        broadcast(saved.eventId(), "ONLINE_SCORE_LOCKED");
        return lockResponse(saved);
    }

    public OnlineStatusResponse status(String eventId, String participantId) {
        requireEvent(eventId);
        String normalizedParticipantId = requiredText(participantId, "参与者标识", 64);
        OnlineScoreRecord record = leaderboardStore.onlineRecord(eventId, normalizedParticipantId);
        if (record == null) {
            return new OnlineStatusResponse(
                    normalizedParticipantId,
                    OnlineLockStatus.NOT_LOCKED,
                    null, null, null, null, null, null, null);
        }
        return new OnlineStatusResponse(
                normalizedParticipantId,
                OnlineLockStatus.LOCKED,
                record.score(),
                record.answeredCount(),
                record.totalQuestions(),
                record.durationMs(),
                leaderboardStore.onlineRank(eventId, normalizedParticipantId),
                leaderboardStore.totalRank(eventId, normalizedParticipantId),
                record.lockedAt());
    }

    public OnlineLeaderboardResponse onlineLeaderboard(String eventId) {
        requireEvent(eventId);
        List<OnlineLeaderboardEntry> entries = leaderboardStore.onlineLeaderboard(eventId);
        return new OnlineLeaderboardResponse(eventId, Instant.now(), entries);
    }

    public TotalLeaderboardResponse totalLeaderboard(String eventId) {
        requireEvent(eventId);
        List<TotalLeaderboardEntry> entries = leaderboardStore.totalLeaderboard(eventId);
        return new TotalLeaderboardResponse(eventId, Instant.now(), entries);
    }

    private OnlineLockResponse lockResponse(OnlineScoreRecord record) {
        return new OnlineLockResponse(
                record.attemptId(),
                record.participantId(),
                record.nickname(),
                record.score(),
                record.answeredCount(),
                record.totalQuestions(),
                record.durationMs(),
                leaderboardStore.onlineRank(record.eventId(), record.participantId()),
                leaderboardStore.totalRank(record.eventId(), record.participantId()),
                record.lockedAt());
    }

    private Submission validate(String eventId, OnlineAttemptPayload payload) {
        Timeline timeline = requireEvent(eventId);
        if (payload == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String participantId = requiredText(payload.participantId(), "参与者标识", 64);
        String nickname = requiredText(payload.nickname(), "昵称", 20);
        String attemptId = requiredText(payload.attemptId(), "答题尝试标识", 64);
        String version = requiredText(payload.questionSetVersion(), "题集版本", 128);
        if (!timelineStore.questionSetVersion().equals(version)) {
            throw new ConflictException("题集版本不一致，请重新加载题目");
        }
        if (payload.durationMs() == null || payload.durationMs() < 0) {
            throw new IllegalArgumentException("作答耗时必须为非负整数");
        }
        if (payload.answers() == null) {
            throw new IllegalArgumentException("答案数组不能为空");
        }
        Set<String> knownQuestionIds = timelineStore.onlineQuestions().stream()
                .map(OnlineQuestionWindow::questionId)
                .collect(Collectors.toSet());
        Map<String, Integer> answers = new LinkedHashMap<>();
        for (OnlineAnswer answer : payload.answers()) {
            if (answer == null) {
                throw new IllegalArgumentException("答案项不能为空");
            }
            String questionId = requiredText(answer.questionId(), "题目编号", 128);
            if (!knownQuestionIds.contains(questionId)) {
                throw new IllegalArgumentException("答案包含未知题目：" + questionId);
            }
            if (answers.containsKey(questionId)) {
                throw new IllegalArgumentException("答案包含重复题目：" + questionId);
            }
            Integer optionIndex = answer.optionIndex();
            if (optionIndex == null || optionIndex < 0 || optionIndex > 3) {
                throw new IllegalArgumentException("选项下标必须取 0 至 3");
            }
            answers.put(questionId, optionIndex);
        }
        return new Submission(
                timeline.eventId(),
                participantId,
                nickname,
                attemptId,
                payload.durationMs(),
                answers);
    }

    private OnlineScoreResult score(Submission submission) {
        int score = 0;
        int answeredCount = 0;
        for (OnlineQuestionWindow window : timelineStore.onlineQuestions()) {
            Integer optionIndex = submission.answers.get(window.questionId());
            if (optionIndex == null) {
                continue;
            }
            answeredCount++;
            Question question = timelineStore.question(window.questionId());
            if (question.correctOption() != null && question.correctOption().equals(optionIndex)) {
                score += POINTS_PER_CORRECT;
            }
        }
        return new OnlineScoreResult(
                score,
                answeredCount,
                timelineStore.onlineQuestions().size(),
                submission.durationMs,
                timelineStore.questionSetVersion());
    }

    private List<OnlineAnswer> normalizedAnswers(Submission submission) {
        List<OnlineAnswer> answers = new ArrayList<>();
        for (OnlineQuestionWindow window : timelineStore.onlineQuestions()) {
            Integer optionIndex = submission.answers.get(window.questionId());
            if (optionIndex != null) {
                answers.add(new OnlineAnswer(window.questionId(), optionIndex));
            }
        }
        return List.copyOf(answers);
    }

    private Timeline requireEvent(String eventId) {
        Timeline timeline = timelineStore.timeline();
        if (eventId == null || !timeline.eventId().equals(eventId)) {
            throw new ResourceNotFoundException("活动不存在");
        }
        return timeline;
    }

    private static String requiredText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过" + maxLength + "个字符");
        }
        return normalized;
    }

    private void broadcast(String eventId, String type) {
        messagingTemplate.convertAndSend(
                "/topic/events/" + eventId,
                Map.of("type", type, "eventId", eventId));
    }

    private static final class Submission {
        private final String eventId;
        private final String participantId;
        private final String nickname;
        private final String attemptId;
        private final long durationMs;
        private final Map<String, Integer> answers;

        private Submission(
                String eventId,
                String participantId,
                String nickname,
                String attemptId,
                long durationMs,
                Map<String, Integer> answers
        ) {
            this.eventId = eventId;
            this.participantId = participantId;
            this.nickname = nickname;
            this.attemptId = attemptId;
            this.durationMs = durationMs;
            this.answers = answers;
        }
    }
}
