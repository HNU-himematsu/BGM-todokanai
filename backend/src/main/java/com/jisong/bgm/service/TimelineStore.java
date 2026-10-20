package com.jisong.bgm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisong.bgm.model.ActionType;
import com.jisong.bgm.model.OnlineQuestionWindow;
import com.jisong.bgm.model.Question;
import com.jisong.bgm.model.Round;
import com.jisong.bgm.model.Timeline;
import com.jisong.bgm.model.TimelineItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 编排文件加载与合法性校验。启动时读取 timeline.json 和同目录 questions.json，
 * 解析题目引用，生成线上题集并计算题集版本。
 */
@Service
public class TimelineStore {
    private static final Logger log = LoggerFactory.getLogger(TimelineStore.class);
    private static final int REQUIRED_OPTION_COUNT = 4;

    private final Timeline timeline;
    private final Map<String, Question> questionBank;
    private final List<OnlineQuestionWindow> onlineQuestions;
    private final String questionSetVersion;
    private final Path audioPath;

    public TimelineStore(
            ObjectMapper objectMapper,
            @Value("${app.timeline-file:data/timeline.json}") String filePath
    ) {
        Path path = resolveFile(filePath);
        Timeline rawTimeline = readTimeline(objectMapper, path);
        validateTopLevel(rawTimeline);
        Map<String, Question> questions = readQuestions(objectMapper, path);
        validateQuestionBank(questions);
        this.timeline = resolveQuestions(rawTimeline, questions);
        validateTimeline(this.timeline);
        this.questionBank = Collections.unmodifiableMap(new LinkedHashMap<>(questions));
        this.onlineQuestions = generateOnlineQuestions(this.timeline);
        validateOnlineQuestions(this.onlineQuestions, this.questionBank);
        this.questionSetVersion = calculateQuestionSetVersion(this.onlineQuestions);
        this.audioPath = resolveAudioPath(path, this.timeline.audioFile());
        if (!Files.isReadable(audioPath)) {
            log.warn("编排文件声明的音频文件不可读，音频流与线上题集接口将返回素材错误：{}", audioPath);
        }
    }

    public Timeline timeline() {
        return timeline;
    }

    public Path audioPath() {
        return audioPath;
    }

    public void requireAudioReadable() {
        if (audioPath == null || !Files.isReadable(audioPath)) {
            throw new ResourceNotFoundException("音频文件不可读：" + audioPath);
        }
    }

    public Question question(String questionId) {
        return questionBank.get(questionId);
    }

    public List<OnlineQuestionWindow> onlineQuestions() {
        return onlineQuestions;
    }

    public String questionSetVersion() {
        return questionSetVersion;
    }

    private static Path resolveFile(String configuredPath) {
        Path direct = Path.of(configuredPath);
        if (direct.isAbsolute()) {
            return direct;
        }
        if (Files.exists(direct)) {
            return direct;
        }
        Path inBackend = Path.of("backend", configuredPath);
        if (Files.exists(inBackend)) {
            return inBackend;
        }
        throw new IllegalStateException("编排文件不存在：" + direct.toAbsolutePath());
    }

    private static Path resolveAudioPath(Path timelinePath, String audioFile) {
        return timelinePath.toAbsolutePath().getParent().resolve(audioFile).normalize();
    }

    private static Timeline readTimeline(ObjectMapper objectMapper, Path path) {
        try {
            return objectMapper.readValue(Files.readAllBytes(path), Timeline.class);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "编排文件解析失败：" + path.toAbsolutePath() + " · " + exception.getMessage(), exception);
        }
    }

    private static Map<String, Question> readQuestions(ObjectMapper objectMapper, Path timelinePath) {
        Path questionsPath = timelinePath.toAbsolutePath().getParent().resolve("questions.json");
        try {
            List<Question> questions = objectMapper.readValue(
                    Files.readAllBytes(questionsPath), new TypeReference<>() {});
            Map<String, Question> byId = new LinkedHashMap<>();
            for (Question question : questions) {
                if (question == null || isBlank(question.id())) {
                    throw new IllegalStateException("题库存在缺少 id 的题目");
                }
                if (byId.putIfAbsent(question.id(), question) != null) {
                    throw new IllegalStateException("题库题目 id 重复：" + question.id());
                }
            }
            return byId;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "题库文件读取或解析失败：" + questionsPath + " · " + exception.getMessage(), exception);
        }
    }

    private static void validateTopLevel(Timeline timeline) {
        if (timeline == null) {
            throw new IllegalStateException("编排文件内容为空");
        }
        if (isBlank(timeline.eventId())) {
            throw new IllegalStateException("编排文件缺少 eventId");
        }
        if (timeline.eventId().contains("/") || timeline.eventId().contains("\\")) {
            throw new IllegalStateException("eventId 不得包含路径分隔符");
        }
        if (isBlank(timeline.eventName())) {
            throw new IllegalStateException("编排文件缺少 eventName");
        }
        if (isBlank(timeline.audioFile())) {
            throw new IllegalStateException("编排文件缺少 audioFile");
        }
        try {
            Path audio = Path.of(timeline.audioFile());
            if (audio.isAbsolute() || audio.getNameCount() != 1 || timeline.audioFile().contains("..")) {
                throw new IllegalStateException("audioFile 必须是与编排文件同目录的相对文件名");
            }
        } catch (InvalidPathException exception) {
            throw new IllegalStateException("audioFile 不是合法路径：" + timeline.audioFile(), exception);
        }
        if (timeline.rounds() == null || timeline.rounds().isEmpty()) {
            throw new IllegalStateException("编排文件 rounds 不能为空");
        }
    }

    private static void validateQuestionBank(Map<String, Question> questions) {
        for (Question question : questions.values()) {
            validateQuestion(question);
            String id = question.id();
            if (id.contains(",") || id.contains("\n") || id.contains("\r")
                    || id.contains("/") || id.contains("\\")) {
                throw new IllegalStateException("题目 id 不得包含逗号、换行或路径分隔符：" + id);
            }
        }
    }

    private static Timeline resolveQuestions(Timeline timeline, Map<String, Question> questions) {
        List<Round> rounds = new ArrayList<>();
        for (Round round : timeline.rounds()) {
            if (round == null) {
                throw new IllegalStateException("编排文件存在空轮次");
            }
            List<TimelineItem> items = new ArrayList<>();
            for (TimelineItem item : round.items()) {
                if (item == null) {
                    throw new IllegalStateException("轮次 " + round.id() + " 存在空条目");
                }
                if (item.action() != ActionType.LOAD) {
                    items.add(item);
                    continue;
                }
                Question question = questions.get(item.questionId());
                if (question == null) {
                    throw new IllegalStateException(
                            "轮次 " + round.id() + " 引用了不存在的题目：" + item.questionId());
                }
                Question occurrence = new Question(
                        question.id(), question.audioCode(), question.prompt(), question.type(),
                        question.difficulty(), question.options(), question.correctOption(),
                        question.workName(), question.explanation(),
                        item.audioStartMs(), item.audioEndMs());
                items.add(item.withQuestion(occurrence));
            }
            rounds.add(new Round(round.id(), round.name(), round.startMs(), round.endMs(), items));
        }
        return new Timeline(timeline.eventId(), timeline.eventName(), timeline.audioFile(), rounds);
    }

    private static void validateTimeline(Timeline timeline) {
        Set<String> roundIds = new HashSet<>();
        Round previousRound = null;
        for (Round round : timeline.rounds()) {
            String position = "轮次 " + round.id();
            if (isBlank(round.id()) || isBlank(round.name())) {
                throw new IllegalStateException("轮次 id 与 name 不能为空");
            }
            if (!roundIds.add(round.id())) {
                throw new IllegalStateException("轮次 id 重复：" + round.id());
            }
            if (round.startMs() < 0 || round.startMs() >= round.endMs()) {
                throw new IllegalStateException(position + " 的时间区间必须满足 0 <= startMs < endMs");
            }
            if (previousRound != null) {
                if (round.startMs() < previousRound.startMs()) {
                    throw new IllegalStateException("轮次必须按 startMs 升序排列");
                }
                if (round.startMs() < previousRound.endMs()) {
                    throw new IllegalStateException(
                            "轮次区间重叠：" + previousRound.id() + " 与 " + round.id());
                }
            }
            if (round.items().isEmpty()) {
                throw new IllegalStateException(position + " 的 items 为空");
            }
            validateRoundItems(round);
            previousRound = round;
        }
    }

    private static void validateRoundItems(Round round) {
        Set<String> questionIds = new HashSet<>();
        long previousAtMs = Long.MIN_VALUE;
        ItemState state = ItemState.EMPTY;
        boolean finishDeclared = false;
        for (int index = 0; index < round.items().size(); index++) {
            TimelineItem item = round.items().get(index);
            String position = "轮次 " + round.id() + " 第 " + (index + 1) + " 条条目";
            if (finishDeclared) {
                throw new IllegalStateException(position + " 位于 FINISH 之后");
            }
            if (item.action() == null) {
                throw new IllegalStateException(position + " 缺少 action");
            }
            if (item.atMs() < round.startMs() || item.atMs() > round.endMs()) {
                throw new IllegalStateException(position + " 的 atMs 超出轮次区间");
            }
            if (item.atMs() <= previousAtMs) {
                throw new IllegalStateException(position + " 的 atMs 未严格递增");
            }
            previousAtMs = item.atMs();
            switch (item.action()) {
                case LOAD -> {
                    if (state != ItemState.EMPTY && state != ItemState.REVEALED) {
                        throw new IllegalStateException(position + " 的 LOAD 前存在未完成题目");
                    }
                    if (isBlank(item.questionId()) || item.question() == null) {
                        throw new IllegalStateException(position + " 的 LOAD 缺少有效 questionId");
                    }
                    if (!questionIds.add(item.questionId())) {
                        throw new IllegalStateException(position + " 重复引用题目：" + item.questionId());
                    }
                    if (item.audioStartMs() == null || item.audioEndMs() == null
                            || item.audioStartMs() >= item.audioEndMs()) {
                        throw new IllegalStateException(position + " 的音频区间必须满足 audioStartMs < audioEndMs");
                    }
                    if (item.audioStartMs() < round.startMs() || item.audioEndMs() > round.endMs()) {
                        throw new IllegalStateException(position + " 的音频区间超出轮次区间");
                    }
                    state = ItemState.LOADED;
                }
                case NEXT -> {
                    if (state != ItemState.LOADED) {
                        throw new IllegalStateException(position + " 的 NEXT 前缺少待上屏 LOAD");
                    }
                    state = ItemState.OPEN;
                }
                case LOCK -> {
                    if (state != ItemState.OPEN) {
                        throw new IllegalStateException(position + " 的 LOCK 前缺少已上屏题目");
                    }
                    state = ItemState.LOCKED;
                }
                case REVEAL -> {
                    if (state != ItemState.LOCKED) {
                        throw new IllegalStateException(position + " 的 REVEAL 前缺少 LOCK");
                    }
                    state = ItemState.REVEALED;
                }
                case FINISH -> {
                    if (state != ItemState.REVEALED) {
                        throw new IllegalStateException(position + " 的 FINISH 前缺少揭晓完成的题目");
                    }
                    finishDeclared = true;
                }
            }
        }
        if (!finishDeclared) {
            throw new IllegalStateException("轮次 " + round.id() + " 缺少 FINISH 条目");
        }
    }

    private static List<OnlineQuestionWindow> generateOnlineQuestions(Timeline timeline) {
        Map<String, Candidate> firstById = new LinkedHashMap<>();
        for (Round round : timeline.rounds()) {
            Candidate current = null;
            for (TimelineItem item : round.items()) {
                switch (item.action()) {
                    case LOAD -> {
                        current = new Candidate(item.questionId(), item.audioStartMs(), item.audioEndMs());
                        firstById.putIfAbsent(item.questionId(), current);
                    }
                    case NEXT -> {
                        if (current == null || current.answerStartMs != null) {
                            throw new IllegalStateException("线上题集生成失败：NEXT 缺少对应 LOAD");
                        }
                        current.answerStartMs = item.atMs();
                    }
                    case LOCK -> {
                        if (current == null || current.answerStartMs == null || current.answerEndMs != null) {
                            throw new IllegalStateException("线上题集生成失败：LOCK 缺少对应 NEXT");
                        }
                        current.answerEndMs = item.atMs();
                    }
                    case REVEAL, FINISH -> current = null;
                }
            }
        }
        List<OnlineQuestionWindow> windows = new ArrayList<>();
        for (Candidate candidate : firstById.values()) {
            if (candidate.answerStartMs == null || candidate.answerEndMs == null) {
                throw new IllegalStateException(
                        "线上题集生成失败：题目 " + candidate.questionId + " 缺少作答窗口");
            }
            windows.add(new OnlineQuestionWindow(
                    candidate.questionId,
                    candidate.audioStartMs,
                    candidate.audioEndMs,
                    candidate.answerStartMs,
                    candidate.answerEndMs));
        }
        return List.copyOf(windows);
    }

    private static void validateOnlineQuestions(
            List<OnlineQuestionWindow> windows,
            Map<String, Question> questionBank
    ) {
        if (windows.isEmpty()) {
            throw new IllegalStateException("线上题集至少需要包含 1 道题");
        }
        Set<String> covered = new HashSet<>();
        for (int index = 0; index < windows.size(); index++) {
            OnlineQuestionWindow window = windows.get(index);
            String position = "线上题集第 " + (index + 1) + " 题 " + window.questionId();
            if (!covered.add(window.questionId())) {
                throw new IllegalStateException(position + " 重复出现");
            }
            if (window.audioStartMs() < 0
                    || window.audioStartMs() > window.answerStartMs()
                    || window.answerStartMs() >= window.answerEndMs()
                    || window.answerEndMs() > window.audioEndMs()) {
                throw new IllegalStateException(
                        position + " 必须满足 audioStartMs <= answerStartMs < answerEndMs <= audioEndMs");
            }
        }
        if (windows.get(0).audioStartMs() != 0L) {
            throw new IllegalStateException("线上题集第一题的 audioStartMs 必须为 0");
        }
        for (int index = 1; index < windows.size(); index++) {
            if (windows.get(index - 1).audioEndMs() != windows.get(index).audioStartMs()) {
                throw new IllegalStateException(
                        "线上题集相邻题目音频未首尾相接："
                                + windows.get(index - 1).questionId() + " 与 " + windows.get(index).questionId());
            }
        }
        if (!covered.equals(questionBank.keySet())) {
            Set<String> unreferenced = new HashSet<>(questionBank.keySet());
            unreferenced.removeAll(covered);
            throw new IllegalStateException("题库题目未被线上题集覆盖：" + unreferenced);
        }
    }

    private static String calculateQuestionSetVersion(List<OnlineQuestionWindow> windows) {
        StringBuilder input = new StringBuilder();
        for (int index = 0; index < windows.size(); index++) {
            OnlineQuestionWindow window = windows.get(index);
            if (index > 0) {
                input.append('\n');
            }
            input.append(window.questionId()).append(',')
                    .append(window.audioStartMs()).append(',')
                    .append(window.audioEndMs()).append(',')
                    .append(window.answerStartMs()).append(',')
                    .append(window.answerEndMs());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexadecimal = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hexadecimal.append(String.format(Locale.ROOT, "%02x", value));
            }
            return hexadecimal.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    private static void validateQuestion(Question question) {
        if (question == null) {
            throw new IllegalStateException("题库存在空题目");
        }
        String position = "题目 " + question.id();
        if (isBlank(question.prompt())) {
            throw new IllegalStateException(position + " 缺少 prompt");
        }
        if (question.type() == null) {
            throw new IllegalStateException(position + " 缺少 type");
        }
        if (question.difficulty() == null) {
            throw new IllegalStateException(position + " 缺少 difficulty");
        }
        if (question.options() == null || question.options().size() != REQUIRED_OPTION_COUNT
                || question.options().stream().anyMatch(TimelineStore::isBlank)) {
            throw new IllegalStateException(position + " 的 options 必须为 4 项非空文本");
        }
        if (question.correctOption() == null
                || question.correctOption() < 0
                || question.correctOption() >= REQUIRED_OPTION_COUNT) {
            throw new IllegalStateException(position + " 的 correctOption 必须取 0 至 3");
        }
        if (isBlank(question.workName())) {
            throw new IllegalStateException(position + " 缺少 workName");
        }
        if (isBlank(question.explanation())) {
            throw new IllegalStateException(position + " 缺少 explanation");
        }
        if (isBlank(question.audioCode())) {
            throw new IllegalStateException(position + " 缺少 audioCode");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private enum ItemState {
        EMPTY,
        LOADED,
        OPEN,
        LOCKED,
        REVEALED
    }

    private static final class Candidate {
        private final String questionId;
        private final Long audioStartMs;
        private final Long audioEndMs;
        private Long answerStartMs;
        private Long answerEndMs;

        private Candidate(String questionId, Long audioStartMs, Long audioEndMs) {
            this.questionId = questionId;
            this.audioStartMs = audioStartMs;
            this.audioEndMs = audioEndMs;
        }
    }
}
