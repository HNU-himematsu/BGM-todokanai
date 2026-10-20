package com.jisong.bgm.service;

import com.jisong.bgm.dto.EventState;
import com.jisong.bgm.dto.HostPlayerView;
import com.jisong.bgm.dto.HostStateResponse;
import com.jisong.bgm.dto.JoinRequest;
import com.jisong.bgm.dto.JoinResponse;
import com.jisong.bgm.dto.LeaderboardEntry;
import com.jisong.bgm.dto.LeaderboardSnapshot;
import com.jisong.bgm.dto.OnlineScoreRecord;
import com.jisong.bgm.dto.QuestionView;
import com.jisong.bgm.dto.RoundListResponse;
import com.jisong.bgm.dto.RoundView;
import com.jisong.bgm.dto.SubmitAnswerRequest;
import com.jisong.bgm.model.ActivityStatus;
import com.jisong.bgm.model.ActionType;
import com.jisong.bgm.model.AppMode;
import com.jisong.bgm.model.Player;
import com.jisong.bgm.model.Question;
import com.jisong.bgm.model.Round;
import com.jisong.bgm.model.Timeline;
import com.jisong.bgm.model.TimelineItem;
import jakarta.annotation.PreDestroy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class ActivityService {
    private static final int POINTS_PER_CORRECT = 10;

    private final SimpMessagingTemplate messagingTemplate;
    private final LeaderboardStore leaderboardStore;
    private final TimelineStore timelineStore;
    private final Map<String, Activity> activities = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bgm-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    public ActivityService(
            SimpMessagingTemplate messagingTemplate,
            LeaderboardStore leaderboardStore,
            TimelineStore timelineStore
    ) {
        this.messagingTemplate = messagingTemplate;
        this.leaderboardStore = leaderboardStore;
        this.timelineStore = timelineStore;
        Timeline timeline = timelineStore.timeline();
        Activity activity = new Activity(timeline.eventId(), timeline.eventName(), timeline.rounds());
        activity.currentRoundId = timeline.rounds().get(0).id();
        activities.put(activity.id, activity);
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
    }

    public Path audioPath(String eventId) {
        getActivity(eventId);
        return timelineStore.audioPath();
    }

    public synchronized ActivitySummary getActivitySummary(String eventId) {
        Activity activity = getActivity(eventId);
        return new ActivitySummary(activity.id, activity.name, questionCount(activity), activity.status);
    }

    public synchronized List<LeaderboardEntry> getLeaderboard(String eventId) {
        Activity activity = getActivity(eventId);
        return activity.status == ActivityStatus.FINISHED ? leaderboard(activity) : List.of();
    }

    public synchronized List<LeaderboardSnapshot> getLeaderboardHistory(String eventId) {
        return leaderboardStore.history(eventId);
    }

    public synchronized String getLeaderboardSummaryTxt() {
        return leaderboardStore.getSummaryTxt();
    }

    public synchronized RoundListResponse listRounds(String eventId) {
        Activity activity = getActivity(eventId);
        return new RoundListResponse(roundViews(activity), activity.currentRoundId);
    }

    public synchronized EventState selectRound(String eventId, String roundId) {
        Activity activity = getActivity(eventId);
        Round round = requireRound(activity, roundId);
        if (activity.timelineRunning) {
            throw new IllegalStateException("时间轴正在运行，无法切换轮次");
        }
        activity.currentRoundId = round.id();
        resetRoom(activity);
        broadcast(activity, "ACTIVITY_RESET");
        return toState(activity, null, false);
    }

    public synchronized JoinResponse join(String eventId, JoinRequest request) {
        Activity activity = getActivity(eventId);
        String nickname = normalizeNickname(request.nickname());
        AppMode mode = request.mode() == null ? AppMode.ONLINE : request.mode();
        String participantId = resolveParticipantId(request.participantId());
        OnlineScoreRecord onlineRecord = leaderboardStore.onlineRecord(eventId, participantId);
        if (mode == AppMode.OFFLINE && activity.status == ActivityStatus.FINISHED) {
            throw new IllegalStateException("本场活动已经结束");
        }
        String playerId = null;
        if (mode == AppMode.OFFLINE) {
            playerId = UUID.randomUUID().toString();
            activity.players.put(playerId, new Player(playerId, participantId, nickname));
            broadcast(activity, "PLAYER_JOINED");
        }
        return new JoinResponse(
                activity.id,
                activity.name,
                mode,
                participantId,
                playerId,
                nickname,
                onlineRecord != null,
                onlineRecord == null ? null : onlineRecord.score(),
                onlineRecord == null ? null : onlineRecord.lockedAt());
    }

    public synchronized EventState getState(String eventId, String playerId) {
        Activity activity = getActivity(eventId);
        Player player = playerId == null || playerId.isBlank() ? null : requirePlayer(activity, playerId);
        return toState(activity, player, false);
    }

    public synchronized HostStateResponse getHostState(String eventId) {
        Activity activity = getActivity(eventId);
        List<HostPlayerView> players = activity.players.values().stream()
                .map(player -> new HostPlayerView(
                        player.getId(),
                        player.getParticipantId(),
                        player.getNickname(),
                        hasSubmitted(activity, player.getId(), activity.currentQuestion),
                        player.getScore(),
                        player.getTotalDurationMs(),
                        player.getJoinedAt()))
                .toList();
        return new HostStateResponse(
                toState(activity, null, true),
                players,
                roundViews(activity),
                activity.currentRoundId,
                activity.timelineRunning);
    }

    public synchronized EventState submitAnswer(String eventId, SubmitAnswerRequest request) {
        Activity activity = getActivity(eventId);
        Player player = requirePlayer(activity, request.playerId());
        requireStatus(activity, ActivityStatus.ANSWERING);
        Question question = activity.currentQuestion;
        if (question == null) {
            throw new IllegalStateException("当前没有上屏题目");
        }
        if (!question.id().equals(request.questionId())) {
            throw new IllegalArgumentException("题目已经切换");
        }
        String key = answerKey(player.getId(), question.id());
        if (activity.answers.containsKey(key)) {
            throw new IllegalStateException("这道题已经提交过答案");
        }
        long now = System.currentTimeMillis();
        long duration = activity.questionNextEpochMs == null ? 0L : Math.max(0L, now - activity.questionNextEpochMs);
        boolean correct = question.correctOption() != null && question.correctOption() == request.optionIndex();
        int points = correct ? POINTS_PER_CORRECT : 0;
        activity.answers.put(key, new AnswerRecord(request.optionIndex(), correct, points, duration));
        player.addScore(points);
        player.addDuration(duration);
        broadcast(activity, "PLAYER_ANSWERED");
        return toState(activity, player, false);
    }

    public synchronized EventState startTimeline(String eventId) {
        Activity activity = getActivity(eventId);
        if (activity.currentRoundId == null) {
            throw new IllegalStateException("尚未选择轮次");
        }
        requireStatus(activity, ActivityStatus.WAITING);
        if (activity.timelineRunning) {
            throw new IllegalStateException("时间轴已经在运行");
        }
        Round round = requireRound(activity, activity.currentRoundId);
        activity.timelineRunning = true;
        activity.answerRevealed = false;
        activity.pendingQuestion = null;
        activity.currentQuestion = null;
        activity.currentQuestionIndex = -1;
        activity.questionAudioStartEpochMs = null;
        activity.questionAudioEndEpochMs = null;
        activity.questionNextEpochMs = null;
        activity.t0EpochMs = System.currentTimeMillis();
        activity.pausedAtEpochMs = null;
        scheduleRound(activity, round);
        broadcast(activity, "ACTIVITY_STARTED");
        return toState(activity, null, false);
    }

    public synchronized EventState pauseTimeline(String eventId) {
        Activity activity = getActivity(eventId);
        if (!activity.timelineRunning) {
            throw new IllegalStateException("时间轴当前未运行");
        }
        activity.timelineRunning = false;
        activity.pausedAtEpochMs = System.currentTimeMillis();
        suspendTasks(activity);
        broadcast(activity, "ACTIVITY_PAUSED");
        return toState(activity, null, false);
    }

    public synchronized EventState resumeTimeline(String eventId) {
        Activity activity = getActivity(eventId);
        if (activity.timelineRunning) {
            throw new IllegalStateException("时间轴已经在运行");
        }
        if (activity.t0EpochMs == null || activity.pausedAtEpochMs == null) {
            throw new IllegalStateException("本轮时间轴尚未启动");
        }
        Round round = requireRound(activity, activity.currentRoundId);
        long now = System.currentTimeMillis();
        long pausedDuration = now - activity.pausedAtEpochMs;
        activity.pausedAtEpochMs = null;
        activity.t0EpochMs += pausedDuration;
        if (activity.questionAudioStartEpochMs != null) {
            activity.questionAudioStartEpochMs += pausedDuration;
        }
        if (activity.questionAudioEndEpochMs != null) {
            activity.questionAudioEndEpochMs += pausedDuration;
        }
        if (activity.questionNextEpochMs != null) {
            activity.questionNextEpochMs += pausedDuration;
        }
        activity.timelineRunning = true;
        for (PendingTask task : activity.pendingTasks) {
            long due = activity.t0EpochMs + (task.atMs - round.startMs());
            task.future = scheduler.schedule(
                    () -> runItem(activity.id, task), Math.max(0L, due - now), TimeUnit.MILLISECONDS);
        }
        long roundEndDue = activity.t0EpochMs + (round.endMs() - round.startMs());
        activity.roundEndFuture = scheduler.schedule(
                () -> runRoundEnd(activity.id, round.id()), Math.max(0L, roundEndDue - now), TimeUnit.MILLISECONDS);
        broadcast(activity, "ACTIVITY_RESUMED");
        return toState(activity, null, false);
    }

    public synchronized EventState lockQuestion(String eventId) {
        Activity activity = getActivity(eventId);
        requireStatus(activity, ActivityStatus.ANSWERING);
        cancelPendingAction(activity, ActionType.LOCK);
        lockCurrentQuestion(activity);
        return toState(activity, null, false);
    }

    public synchronized EventState revealAnswer(String eventId) {
        Activity activity = getActivity(eventId);
        requireStatus(activity, ActivityStatus.ANSWER_LOCKED);
        cancelPendingAction(activity, ActionType.REVEAL);
        revealCurrentQuestion(activity);
        return toState(activity, null, false);
    }

    public synchronized EventState nextQuestion(String eventId) {
        Activity activity = getActivity(eventId);
        if (activity.status != ActivityStatus.WAITING && activity.status != ActivityStatus.ANSWER_REVEALED) {
            throw new IllegalStateException("仅等待或揭晓状态下可以上屏下一题");
        }
        if (activity.pendingQuestion == null) {
            throw new IllegalStateException("待上屏题目尚未预加载");
        }
        cancelPendingAction(activity, ActionType.NEXT);
        openQuestion(activity, requireRound(activity, activity.currentRoundId));
        return toState(activity, null, false);
    }

    public synchronized EventState reset(String eventId) {
        Activity activity = getActivity(eventId);
        resetRoom(activity);
        broadcast(activity, "ACTIVITY_RESET");
        return toState(activity, null, false);
    }

    private void scheduleRound(Activity activity, Round round) {
        clearTasks(activity);
        long now = System.currentTimeMillis();
        for (int index = 0; index < round.items().size(); index++) {
            TimelineItem item = round.items().get(index);
            long due = activity.t0EpochMs + (item.atMs() - round.startMs());
            PendingTask task = new PendingTask(round.id(), index, item.atMs(), item.action());
            task.future = scheduler.schedule(
                    () -> runItem(activity.id, task), Math.max(0L, due - now), TimeUnit.MILLISECONDS);
            activity.pendingTasks.add(task);
        }
        long roundEndDue = activity.t0EpochMs + (round.endMs() - round.startMs());
        activity.roundEndFuture = scheduler.schedule(
                () -> runRoundEnd(activity.id, round.id()), Math.max(0L, roundEndDue - now), TimeUnit.MILLISECONDS);
    }

    private synchronized void runItem(String eventId, PendingTask task) {
        Activity activity = activities.get(eventId);
        if (activity == null || !activity.timelineRunning || !task.roundId.equals(activity.currentRoundId)) {
            return;
        }
        activity.pendingTasks.remove(task);
        Round round = round(activity, task.roundId);
        if (round == null) {
            return;
        }
        TimelineItem item = round.items().get(task.itemIndex);
        switch (item.action()) {
            case LOAD -> activity.pendingQuestion = item.question();
            case NEXT -> {
                if (takesNext(activity) && activity.pendingQuestion != null) {
                    openQuestion(activity, round);
                }
            }
            case LOCK -> {
                if (activity.status == ActivityStatus.ANSWERING) {
                    lockCurrentQuestion(activity);
                }
            }
            case REVEAL -> {
                if (activity.status == ActivityStatus.ANSWER_LOCKED) {
                    revealCurrentQuestion(activity);
                }
            }
            case FINISH -> finishRound(activity);
        }
    }

    private synchronized void runRoundEnd(String eventId, String roundId) {
        Activity activity = activities.get(eventId);
        if (activity == null || !activity.timelineRunning || !roundId.equals(activity.currentRoundId)) {
            return;
        }
        activity.timelineRunning = false;
        activity.roundEndFuture = null;
        clearPendingItems(activity);
        broadcast(activity, "ROUND_ENDED");
    }

    private void openQuestion(Activity activity, Round round) {
        Question question = activity.pendingQuestion;
        if (question == null) {
            return;
        }
        activity.pendingQuestion = null;
        activity.currentQuestion = question;
        activity.currentQuestionIndex++;
        activity.status = ActivityStatus.ANSWERING;
        activity.answerRevealed = false;
        activity.questionNextEpochMs = System.currentTimeMillis();
        if (activity.t0EpochMs == null) {
            activity.questionAudioStartEpochMs = null;
            activity.questionAudioEndEpochMs = null;
        } else {
            activity.questionAudioStartEpochMs = activity.t0EpochMs + (question.audioStartMs() - round.startMs());
            activity.questionAudioEndEpochMs = activity.t0EpochMs + (question.audioEndMs() - round.startMs());
        }
        broadcast(activity, "QUESTION_STARTED");
    }

    private void lockCurrentQuestion(Activity activity) {
        Question question = activity.currentQuestion;
        if (question != null) {
            long now = System.currentTimeMillis();
            long openedAt = activity.questionNextEpochMs == null ? now : activity.questionNextEpochMs;
            for (Player player : activity.players.values()) {
                if (hasSubmitted(activity, player.getId(), question)) {
                    continue;
                }
                long startedAt = Math.max(openedAt, player.getJoinedAtEpochMs());
                long duration = now - startedAt;
                if (duration > 0) {
                    player.addDuration(duration);
                }
            }
        }
        activity.status = ActivityStatus.ANSWER_LOCKED;
        broadcast(activity, "ANSWER_LOCKED");
    }

    private void revealCurrentQuestion(Activity activity) {
        activity.status = ActivityStatus.ANSWER_REVEALED;
        activity.answerRevealed = true;
        broadcast(activity, "ANSWER_REVEALED");
    }

    private void finishRound(Activity activity) {
        clearPendingItems(activity);
        activity.status = ActivityStatus.FINISHED;
        activity.answerRevealed = true;
        if (!activity.leaderboardSaved) {
            Round round = requireRound(activity, activity.currentRoundId);
            leaderboardStore.save(
                    activity.id, activity.name, round.id(), round.name(), leaderboard(activity));
            activity.leaderboardSaved = true;
        }
        broadcast(activity, "ACTIVITY_FINISHED");
    }

    private void resetRoom(Activity activity) {
        clearTasks(activity);
        activity.players.clear();
        activity.answers.clear();
        activity.status = ActivityStatus.WAITING;
        activity.currentQuestionIndex = -1;
        activity.pendingQuestion = null;
        activity.currentQuestion = null;
        activity.questionAudioStartEpochMs = null;
        activity.questionAudioEndEpochMs = null;
        activity.questionNextEpochMs = null;
        activity.answerRevealed = false;
        activity.timelineRunning = false;
        activity.t0EpochMs = null;
        activity.pausedAtEpochMs = null;
        activity.leaderboardSaved = false;
    }

    private void clearTasks(Activity activity) {
        clearPendingItems(activity);
        cancel(activity.roundEndFuture);
        activity.roundEndFuture = null;
    }

    private void clearPendingItems(Activity activity) {
        for (PendingTask task : activity.pendingTasks) {
            cancel(task.future);
        }
        activity.pendingTasks.clear();
    }

    private void suspendTasks(Activity activity) {
        for (PendingTask task : activity.pendingTasks) {
            cancel(task.future);
        }
        cancel(activity.roundEndFuture);
        activity.roundEndFuture = null;
    }

    private void cancelPendingAction(Activity activity, ActionType action) {
        activity.pendingTasks.removeIf(task -> {
            if (task.action != action) {
                return false;
            }
            cancel(task.future);
            return true;
        });
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private EventState toState(Activity activity, Player player, boolean hostView) {
        boolean showLeaderboard = activity.status == ActivityStatus.FINISHED;
        List<LeaderboardEntry> leaderboard = showLeaderboard ? leaderboard(activity) : List.of();
        int playerRank = 0;
        if (showLeaderboard && player != null) {
            playerRank = leaderboard.stream()
                    .filter(entry -> entry.playerId().equals(player.getId()))
                    .map(LeaderboardEntry::rank)
                    .findFirst()
                    .orElse(0);
        }
        Question question = activity.currentQuestion;
        boolean revealAnswer = activity.answerRevealed;
        boolean showWorkName = hostView || revealAnswer;
        QuestionView questionView = question == null ? null : new QuestionView(
                question.id(),
                question.prompt(),
                question.difficulty(),
                question.type(),
                question.options(),
                showWorkName ? question.workName() : null,
                revealAnswer ? question.explanation() : null,
                revealAnswer ? question.correctOption() : null);
        AnswerRecord answer = player == null || question == null
                ? null
                : activity.answers.get(answerKey(player.getId(), question.id()));
        boolean submitted = answer != null && answer.optionIndex() >= 0;
        return new EventState(
                activity.id,
                activity.name,
                activity.status,
                activity.currentQuestionIndex,
                questionCount(activity),
                activity.questionAudioStartEpochMs,
                activity.questionAudioEndEpochMs,
                activity.answerRevealed,
                activity.timelineRunning,
                player != null && activity.status == ActivityStatus.ANSWERING,
                questionView,
                submitted,
                submitted ? answer.optionIndex() : null,
                showLeaderboard && player != null ? player.getScore() : 0,
                player != null ? player.getTotalDurationMs() : 0L,
                playerRank,
                activity.players.size(),
                answeredCount(activity, question),
                leaderboard);
    }

    private List<RoundView> roundViews(Activity activity) {
        List<RoundView> views = new ArrayList<>();
        for (Round round : activity.rounds) {
            views.add(new RoundView(
                    round.id(), round.name(), round.startMs(), round.endMs(), round.questionCount()));
        }
        return views;
    }

    private int questionCount(Activity activity) {
        Round round = round(activity, activity.currentRoundId);
        return round == null ? 0 : round.questionCount();
    }

    private boolean hasSubmitted(Activity activity, String playerId, Question question) {
        if (question == null) {
            return false;
        }
        AnswerRecord answer = activity.answers.get(answerKey(playerId, question.id()));
        return answer != null && answer.optionIndex() >= 0;
    }

    private int answeredCount(Activity activity, Question question) {
        if (question == null) {
            return 0;
        }
        int count = 0;
        for (Player player : activity.players.values()) {
            if (hasSubmitted(activity, player.getId(), question)) {
                count++;
            }
        }
        return count;
    }

    private List<LeaderboardEntry> leaderboard(Activity activity) {
        List<Player> players = new ArrayList<>(activity.players.values());
        players.sort(Comparator.comparingInt(Player::getScore).reversed()
                .thenComparingLong(Player::getTotalDurationMs)
                .thenComparing(Player::getJoinedAt)
                .thenComparing(Player::getNickname));
        List<LeaderboardEntry> entries = new ArrayList<>();
        for (int index = 0; index < players.size(); index++) {
            Player player = players.get(index);
            entries.add(new LeaderboardEntry(
                    index + 1,
                    player.getParticipantId(),
                    player.getId(),
                    player.getNickname(),
                    player.getScore(),
                    player.getTotalDurationMs(),
                    answeredCount(activity, player.getId()),
                    questionCount(activity),
                    player.getJoinedAt()));
        }
        return entries;
    }

    private int answeredCount(Activity activity, String playerId) {
        String prefix = playerId + ":";
        int count = 0;
        for (String key : activity.answers.keySet()) {
            if (key.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private void broadcast(Activity activity, String type) {
        messagingTemplate.convertAndSend(
                "/topic/events/" + activity.id,
                Map.of("type", type, "eventId", activity.id));
    }

    private Activity getActivity(String eventId) {
        Activity activity = activities.get(eventId);
        if (activity == null) {
            throw new ResourceNotFoundException("活动不存在");
        }
        return activity;
    }

    private Player requirePlayer(Activity activity, String playerId) {
        Player player = activity.players.get(playerId);
        if (player == null) {
            throw new IllegalArgumentException("参与者身份无效，请重新加入活动");
        }
        return player;
    }

    private void requireStatus(Activity activity, ActivityStatus expected) {
        if (activity.status != expected) {
            throw new IllegalStateException("当前活动状态不允许此操作");
        }
    }

    private Round requireRound(Activity activity, String roundId) {
        Round round = round(activity, roundId);
        if (round == null) {
            throw new IllegalArgumentException("轮次不存在");
        }
        return round;
    }

    private Round round(Activity activity, String roundId) {
        if (roundId == null) {
            return null;
        }
        for (Round round : activity.rounds) {
            if (round.id().equals(roundId)) {
                return round;
            }
        }
        return null;
    }

    private static boolean takesNext(Activity activity) {
        return activity.status == ActivityStatus.WAITING || activity.status == ActivityStatus.ANSWER_REVEALED;
    }

    private static String answerKey(String playerId, String questionId) {
        return playerId + ":" + questionId;
    }

    private static String normalizeNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("昵称不能为空");
        }
        String normalized = nickname.trim();
        if (normalized.length() > 20) {
            throw new IllegalArgumentException("昵称不能超过20个字符");
        }
        return normalized;
    }

    private static String resolveParticipantId(String participantId) {
        if (participantId == null) {
            return UUID.randomUUID().toString();
        }
        if (participantId.isBlank()) {
            throw new IllegalArgumentException("参与者标识不能为空");
        }
        String normalized = participantId.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("参与者标识不能超过64个字符");
        }
        return normalized;
    }

    public record ActivitySummary(String id, String name, int totalQuestions, ActivityStatus status) {
    }

    private static final class Activity {
        private final String id;
        private final String name;
        private final List<Round> rounds;
        private final Map<String, Player> players = new LinkedHashMap<>();
        private final Map<String, AnswerRecord> answers = new HashMap<>();
        private final List<PendingTask> pendingTasks = new ArrayList<>();
        private ActivityStatus status = ActivityStatus.WAITING;
        private String currentRoundId;
        private int currentQuestionIndex = -1;
        private Question pendingQuestion;
        private Question currentQuestion;
        private Long questionAudioStartEpochMs;
        private Long questionAudioEndEpochMs;
        private Long questionNextEpochMs;
        private Long t0EpochMs;
        private Long pausedAtEpochMs;
        private boolean answerRevealed;
        private boolean timelineRunning;
        private boolean leaderboardSaved;
        private ScheduledFuture<?> roundEndFuture;

        private Activity(String id, String name, List<Round> rounds) {
            this.id = id;
            this.name = name;
            this.rounds = rounds;
        }
    }

    private static final class PendingTask {
        private final String roundId;
        private final int itemIndex;
        private final long atMs;
        private final ActionType action;
        private ScheduledFuture<?> future;

        private PendingTask(String roundId, int itemIndex, long atMs, ActionType action) {
            this.roundId = roundId;
            this.itemIndex = itemIndex;
            this.atMs = atMs;
            this.action = action;
        }
    }

    private record AnswerRecord(int optionIndex, boolean correct, int points, long durationMs) {
    }
}
