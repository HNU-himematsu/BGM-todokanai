package com.jisong.bgm.service;

import com.jisong.bgm.dto.EventState;
import com.jisong.bgm.dto.JoinResponse;
import com.jisong.bgm.dto.LeaderboardEntry;
import com.jisong.bgm.dto.QuestionView;
import com.jisong.bgm.dto.SubmitAnswerRequest;
import com.jisong.bgm.model.ActivityStatus;
import com.jisong.bgm.model.Difficulty;
import com.jisong.bgm.model.Player;
import com.jisong.bgm.model.Question;
import jakarta.annotation.PreDestroy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
    private static final String DEMO_EVENT_ID = "demo";
    private static final String DEMO_EVENT_NAME = "旮旯原声之届不到的 BGM";
    private static final int QUESTION_SECONDS = 20;
    private static final int REVEAL_SECONDS = 6;

    private final SimpMessagingTemplate messagingTemplate;
    private final LeaderboardStore leaderboardStore;
    private final QuestionBank questionBank;
    private final Map<String, Activity> activities = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bgm-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    public ActivityService(
            SimpMessagingTemplate messagingTemplate,
            LeaderboardStore leaderboardStore,
            QuestionBank questionBank
    ) {
        this.messagingTemplate = messagingTemplate;
        this.leaderboardStore = leaderboardStore;
        this.questionBank = questionBank;
        activities.put(DEMO_EVENT_ID, new Activity(DEMO_EVENT_ID, DEMO_EVENT_NAME, questionBank.questions()));
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
    }

    public synchronized ActivitySummary getActivitySummary(String eventId) {
        Activity activity = getActivity(eventId);
        return new ActivitySummary(activity.id, activity.name, activity.questions.size(), activity.status);
    }

    public synchronized List<LeaderboardEntry> getLeaderboard(String eventId) {
        Activity activity = getActivity(eventId);
        return activity.status == ActivityStatus.FINISHED ? leaderboard(activity) : List.of();
    }

    public synchronized List<com.jisong.bgm.dto.LeaderboardSnapshot> getLeaderboardHistory(String eventId) {
        return leaderboardStore.history(eventId);
    }

    public synchronized String getLeaderboardSummaryTxt() {
        return leaderboardStore.getSummaryTxt();
    }

    public synchronized JoinResponse join(String eventId, String nickname) {
        Activity activity = getActivity(eventId);
        if (activity.status == ActivityStatus.FINISHED) {
            throw new IllegalStateException("本场活动已经结束");
        }

        String playerId = UUID.randomUUID().toString();
        boolean canAnswer = !activity.startedOnce;
        activity.players.put(playerId, new Player(playerId, nickname.trim(), canAnswer));
        broadcast(activity, "PLAYER_JOINED");
        return new JoinResponse(eventId, playerId, nickname.trim());
    }

    public synchronized EventState getState(String eventId, String playerId) {
        Activity activity = getActivity(eventId);
        Player player = playerId == null ? null : activity.players.get(playerId);
        Question question = currentQuestion(activity);
        AnswerRecord answer = player == null || question == null
                ? null
                : activity.answers.get(answerKey(player.getId(), question.id()));

        return toState(activity, player, question, answer);
    }

    public synchronized EventState submitAnswer(String eventId, SubmitAnswerRequest request) {
        Activity activity = getActivity(eventId);
        Player player = requirePlayer(activity, request.playerId());
        Question question = currentQuestion(activity);

        if (!player.canAnswer()) {
            throw new IllegalStateException("你是在答题开始后加入的，本轮只能观看");
        }
        if (activity.status != ActivityStatus.ANSWERING || question == null) {
            throw new IllegalStateException("当前不是答题时间");
        }
        if (!question.id().equals(request.questionId())) {
            throw new IllegalArgumentException("题目已经切换");
        }
        if (activity.deadline == null || Instant.now().toEpochMilli() > activity.deadline) {
            throw new IllegalStateException("答题时间已结束");
        }

        String key = answerKey(player.getId(), question.id());
        if (activity.answers.containsKey(key)) {
            throw new IllegalStateException("这道题已经提交过答案");
        }

        long now = Instant.now().toEpochMilli();
        long duration = Math.max(0, now - activity.questionStartTime);
        boolean correct = question.correctOption() == request.optionIndex();
        int points = correct ? pointsFor(question.difficulty()) : 0;
        activity.answers.put(key, new AnswerRecord(request.optionIndex(), correct, points, duration));
        player.addScore(points);
        player.addDuration(duration);
        broadcast(activity, "LEADERBOARD_UPDATED");
        return getState(eventId, player.getId());
    }

    public synchronized EventState startQuestion(String eventId) {
        Activity activity = getActivity(eventId);
        if (activity.status == ActivityStatus.FINISHED) {
            throw new IllegalStateException("本场活动已经结束，请先重置");
        }
        if (activity.currentQuestionIndex < 0) {
            activity.currentQuestionIndex = 0;
        }
        if (activity.currentQuestionIndex >= activity.questions.size()) {
            finishActivity(activity);
        } else {
            startQuestionInternal(activity);
        }
        return getState(eventId, null);
    }

    private void startQuestionInternal(Activity activity) {
        cancelTimer(activity);
        activity.startedOnce = true;
        activity.status = ActivityStatus.ANSWERING;
        activity.answerRevealed = false;
        activity.questionStartTime = Instant.now().toEpochMilli();
        activity.deadline = activity.questionStartTime + (QUESTION_SECONDS * 1000L);

        if (activity.autoMode) {
            final int expectedIndex = activity.currentQuestionIndex;
            final String eventId = activity.id;
            activity.currentScheduledTask = scheduler.schedule(
                    () -> handleQuestionTimeout(eventId, expectedIndex),
                    QUESTION_SECONDS,
                    TimeUnit.SECONDS
            );
        }
        broadcast(activity, "QUESTION_STARTED");
    }

    public synchronized void handleQuestionTimeout(String eventId, int expectedIndex) {
        Activity activity = activities.get(eventId);
        if (activity == null || activity.status != ActivityStatus.ANSWERING || activity.currentQuestionIndex != expectedIndex) {
            return;
        }
        cancelTimer(activity);
        Question question = currentQuestion(activity);
        if (question != null) {
            recordTimeoutsForCurrentQuestion(activity, question);
        }
        activity.status = ActivityStatus.REVEALING;
        activity.answerRevealed = true;
        activity.deadline = null;

        if (activity.autoMode) {
            final int nextIndex = activity.currentQuestionIndex;
            activity.currentScheduledTask = scheduler.schedule(
                    () -> handleRevealTimeout(eventId, nextIndex),
                    REVEAL_SECONDS,
                    TimeUnit.SECONDS
            );
        }
        broadcast(activity, "ANSWER_REVEALED");
    }

    public synchronized void handleRevealTimeout(String eventId, int expectedIndex) {
        Activity activity = activities.get(eventId);
        if (activity == null || activity.status != ActivityStatus.REVEALING || activity.currentQuestionIndex != expectedIndex) {
            return;
        }
        cancelTimer(activity);
        if (!activity.autoMode) {
            return;
        }
        activity.currentQuestionIndex++;
        if (activity.currentQuestionIndex >= activity.questions.size()) {
            finishActivity(activity);
            broadcast(activity, "ACTIVITY_FINISHED");
        } else {
            startQuestionInternal(activity);
        }
    }

    public synchronized EventState endAnswer(String eventId) {
        Activity activity = getActivity(eventId);
        requireStatus(activity, ActivityStatus.ANSWERING);
        cancelTimer(activity);
        Question question = currentQuestion(activity);
        if (question != null) {
            recordTimeoutsForCurrentQuestion(activity, question);
        }
        activity.status = ActivityStatus.REVEALING;
        activity.deadline = null;
        activity.answerRevealed = true;

        if (activity.autoMode) {
            final int nextIndex = activity.currentQuestionIndex;
            activity.currentScheduledTask = scheduler.schedule(
                    () -> handleRevealTimeout(eventId, nextIndex),
                    REVEAL_SECONDS,
                    TimeUnit.SECONDS
            );
        }
        broadcast(activity, "ANSWER_REVEALED");
        return getState(eventId, null);
    }

    public synchronized EventState reveal(String eventId) {
        Activity activity = getActivity(eventId);
        requireStatus(activity, ActivityStatus.REVEALING);
        activity.answerRevealed = true;
        cancelTimer(activity);
        if (activity.autoMode) {
            final int nextIndex = activity.currentQuestionIndex;
            activity.currentScheduledTask = scheduler.schedule(
                    () -> handleRevealTimeout(eventId, nextIndex),
                    REVEAL_SECONDS,
                    TimeUnit.SECONDS
            );
        }
        broadcast(activity, "ANSWER_REVEALED");
        return getState(eventId, null);
    }

    public synchronized EventState nextQuestion(String eventId) {
        Activity activity = getActivity(eventId);
        if (activity.status != ActivityStatus.REVEALING && activity.status != ActivityStatus.WAITING) {
            throw new IllegalStateException("请先结束当前题目");
        }

        cancelTimer(activity);
        activity.currentQuestionIndex++;
        if (activity.currentQuestionIndex >= activity.questions.size()) {
            finishActivity(activity);
            broadcast(activity, "ACTIVITY_FINISHED");
        } else {
            if (activity.autoMode) {
                startQuestionInternal(activity);
            } else {
                activity.status = ActivityStatus.WAITING;
                activity.answerRevealed = false;
                activity.deadline = null;
                broadcast(activity, "ACTIVITY_STATE_CHANGED");
            }
        }
        return getState(eventId, null);
    }

    public synchronized EventState pauseAuto(String eventId) {
        Activity activity = getActivity(eventId);
        activity.autoMode = false;
        cancelTimer(activity);
        broadcast(activity, "ACTIVITY_STATE_CHANGED");
        return getState(eventId, null);
    }

    public synchronized EventState resumeAuto(String eventId) {
        Activity activity = getActivity(eventId);
        if (activity.status == ActivityStatus.FINISHED) {
            throw new IllegalStateException("本场活动已经结束，请先重置");
        }
        activity.autoMode = true;
        cancelTimer(activity);

        if (activity.status == ActivityStatus.WAITING) {
            startQuestion(eventId);
        } else if (activity.status == ActivityStatus.ANSWERING) {
            long now = Instant.now().toEpochMilli();
            long remainingMs = activity.deadline != null ? activity.deadline - now : 0L;
            if (remainingMs <= 0) {
                handleQuestionTimeout(eventId, activity.currentQuestionIndex);
            } else {
                final int expectedIndex = activity.currentQuestionIndex;
                activity.currentScheduledTask = scheduler.schedule(
                        () -> handleQuestionTimeout(eventId, expectedIndex),
                        remainingMs,
                        TimeUnit.MILLISECONDS
                );
            }
        } else if (activity.status == ActivityStatus.REVEALING) {
            final int nextIndex = activity.currentQuestionIndex;
            activity.currentScheduledTask = scheduler.schedule(
                    () -> handleRevealTimeout(eventId, nextIndex),
                    REVEAL_SECONDS,
                    TimeUnit.SECONDS
            );
        }
        broadcast(activity, "ACTIVITY_STATE_CHANGED");
        return getState(eventId, null);
    }

    public synchronized EventState setAutoMode(String eventId, boolean enabled) {
        return enabled ? resumeAuto(eventId) : pauseAuto(eventId);
    }

    public synchronized EventState reset(String eventId) {
        Activity activity = getActivity(eventId);
        cancelTimer(activity);
        activity.currentQuestionIndex = -1;
        activity.status = ActivityStatus.WAITING;
        activity.answerRevealed = false;
        activity.deadline = null;
        activity.startedOnce = false;
        activity.leaderboardSaved = false;
        activity.autoMode = true;
        activity.answers.clear();
        activity.players.clear();
        broadcast(activity, "ACTIVITY_STATE_CHANGED");
        return getState(eventId, null);
    }

    private ActivitySummary toSummary(Activity activity) {
        return new ActivitySummary(activity.id, activity.name, activity.questions.size(), activity.status);
    }

    private EventState toState(Activity activity, Player player, Question question, AnswerRecord answer) {
        boolean showLeaderboard = activity.status == ActivityStatus.FINISHED;
        List<LeaderboardEntry> leaderboard = showLeaderboard ? leaderboard(activity) : List.of();
        int playerRank = !showLeaderboard || player == null ? 0 : leaderboard.stream()
                .filter(entry -> entry.playerId().equals(player.getId()))
                .map(LeaderboardEntry::rank)
                .findFirst()
                .orElse(0);

        boolean revealAnswer = activity.answerRevealed;
        int currentQuestionNo = activity.status == ActivityStatus.FINISHED
                ? activity.questions.size()
                : activity.currentQuestionIndex < 0 ? 0 : activity.currentQuestionIndex + 1;
        QuestionView questionView = question == null ? null : new QuestionView(
                question.id(),
                question.audioCode(),
                question.prompt(),
                question.type(),
                question.difficulty(),
                question.options(),
                revealAnswer ? question.workName() : null,
                revealAnswer ? question.explanation() : null,
                revealAnswer ? question.correctOption() : null
        );

        return new EventState(
                activity.id,
                activity.name,
                activity.status,
                currentQuestionNo,
                activity.questions.size(),
                activity.deadline,
                activity.answerRevealed,
                player != null && player.canAnswer(),
                questionView,
                answer != null,
                answer == null ? null : answer.optionIndex(),
                showLeaderboard && player != null ? player.getScore() : 0,
                player != null ? player.getTotalDurationMs() : 0L,
                playerRank,
                activity.players.size(),
                activity.autoMode,
                leaderboard
        );
    }

    private void finishActivity(Activity activity) {
        cancelTimer(activity);
        activity.status = ActivityStatus.FINISHED;
        activity.answerRevealed = true;
        activity.deadline = null;
        if (!activity.leaderboardSaved) {
            leaderboardStore.save(activity.id, activity.name, leaderboard(activity));
            activity.leaderboardSaved = true;
        }
    }

    private List<LeaderboardEntry> leaderboard(Activity activity) {
        List<Player> players = activity.players.values().stream()
                .filter(Player::canAnswer)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        players.sort(Comparator.comparingInt(Player::getScore).reversed()
                .thenComparingLong(Player::getTotalDurationMs)
                .thenComparing(Player::getJoinedAt)
                .thenComparing(Player::getNickname));

        List<LeaderboardEntry> entries = new ArrayList<>();
        for (int index = 0; index < players.size(); index++) {
            Player player = players.get(index);
            entries.add(new LeaderboardEntry(
                    index + 1,
                    player.getId(),
                    player.getNickname(),
                    player.getScore(),
                    player.getTotalDurationMs()
            ));
        }
        return entries;
    }

    private void broadcast(Activity activity, String type) {
        messagingTemplate.convertAndSend(
                "/topic/events/" + activity.id,
                Map.of("type", type, "eventId", activity.id)
        );
    }

    private Activity getActivity(String eventId) {
        Activity activity = activities.get(eventId);
        if (activity == null) {
            throw new IllegalArgumentException("活动不存在");
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

    private Question currentQuestion(Activity activity) {
        if (activity.currentQuestionIndex < 0 || activity.currentQuestionIndex >= activity.questions.size()) {
            return null;
        }
        return activity.questions.get(activity.currentQuestionIndex);
    }

    private static int pointsFor(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> 10;
            case MEDIUM -> 20;
            case HARD -> 30;
        };
    }

    private static String answerKey(String playerId, String questionId) {
        return playerId + ":" + questionId;
    }

    private void recordTimeoutsForCurrentQuestion(Activity activity, Question question) {
        long timeoutDuration = QUESTION_SECONDS * 1000L;
        for (Player player : activity.players.values()) {
            if (player.canAnswer()) {
                String key = answerKey(player.getId(), question.id());
                if (!activity.answers.containsKey(key)) {
                    activity.answers.put(key, new AnswerRecord(-1, false, 0, timeoutDuration));
                    player.addDuration(timeoutDuration);
                }
            }
        }
    }

    private void cancelTimer(Activity activity) {
        if (activity.currentScheduledTask != null) {
            activity.currentScheduledTask.cancel(false);
            activity.currentScheduledTask = null;
        }
    }

    public record ActivitySummary(String id, String name, int totalQuestions, ActivityStatus status) {
    }

    private static final class Activity {
        private final String id;
        private final String name;
        private final List<Question> questions;
        private final Map<String, Player> players = new LinkedHashMap<>();
        private final Map<String, AnswerRecord> answers = new HashMap<>();
        private ActivityStatus status = ActivityStatus.WAITING;
        private int currentQuestionIndex = -1;
        private Long deadline;
        private long questionStartTime;
        private boolean answerRevealed;
        private boolean startedOnce;
        private boolean leaderboardSaved;
        private boolean autoMode = true;
        private ScheduledFuture<?> currentScheduledTask;

        private Activity(String id, String name, List<Question> questions) {
            this.id = id;
            this.name = name;
            this.questions = questions;
        }
    }

    private record AnswerRecord(int optionIndex, boolean correct, int points, long durationMs) {
    }
}
