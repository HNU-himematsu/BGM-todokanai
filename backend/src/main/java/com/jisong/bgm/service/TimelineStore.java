package com.jisong.bgm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.jisong.bgm.model.ActionType;
import com.jisong.bgm.model.Question;
import com.jisong.bgm.model.Round;
import com.jisong.bgm.model.Timeline;
import com.jisong.bgm.model.TimelineItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * 编排文件加载与合法性校验：启动时读取 timeline.json 和 questions.json，并按题目编号关联题目内容。
 * 读取或校验失败时抛出启动异常，异常信息包含具体轮次与条目位置。
 */
@Service
public class TimelineStore {
    private static final Logger log = LoggerFactory.getLogger(TimelineStore.class);
    private static final int REQUIRED_OPTION_COUNT = 4;

    private final Timeline timeline;
    private final Path audioPath;

    public TimelineStore(
            ObjectMapper objectMapper,
            @Value("${app.timeline-file:data/timeline.json}") String filePath
    ) {
        Path path = resolveFile(filePath);
        Timeline rawTimeline = readTimeline(objectMapper, path);
        Map<String, Question> questions = readQuestions(objectMapper, path);
        this.timeline = resolveQuestions(rawTimeline, questions);
        validate(this.timeline);
        this.audioPath = path.toAbsolutePath().getParent().resolve(this.timeline.audioFile());
        if (!Files.isReadable(audioPath)) {
            log.warn("编排文件声明的音频文件不可读，音频流接口本次无法输出音频：{}", audioPath);
        }
    }

    public Timeline timeline() {
        return timeline;
    }

    public Path audioPath() {
        return audioPath;
    }

    private static Path resolveFile(String configuredPath) {
        Path direct = Path.of(configuredPath);
        if (Files.exists(direct)) {
            return direct;
        }
        Path inBackend = Path.of("backend", configuredPath);
        if (Files.exists(inBackend)) {
            return inBackend;
        }
        throw new IllegalStateException("编排文件不存在：" + direct.toAbsolutePath());
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
            Map<String, Question> byId = new HashMap<>();
            for (Question question : questions) {
                if (question.id() == null || question.id().isBlank()) {
                    throw new IllegalStateException("题库存在缺少 id 的题目");
                }
                if (byId.putIfAbsent(question.id(), question) != null) {
                    throw new IllegalStateException("题库题目 id 重复：" + question.id());
                }
            }
            return Map.copyOf(byId);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "题库文件读取或解析失败：" + questionsPath + " · " + exception.getMessage(), exception);
        }
    }

    private static Timeline resolveQuestions(Timeline timeline, Map<String, Question> questions) {
        List<Round> rounds = timeline.rounds().stream().map(round -> {
            List<TimelineItem> items = round.items().stream().map(item -> {
                if (item.action() != ActionType.LOAD) {
                    return item;
                }
                Question question = questions.get(item.questionId());
                if (question == null) {
                    throw new IllegalStateException("轮次 " + round.id() + " 引用了不存在的题目：" + item.questionId());
                }
                Question occurrence = new Question(
                        question.id(), question.audioCode(), question.prompt(), question.type(), question.difficulty(),
                        question.options(), question.correctOption(), question.workName(), question.explanation(),
                        item.audioStartMs(), item.audioEndMs());
                return item.withQuestion(occurrence);
            }).toList();
            return new Round(round.id(), round.name(), round.startMs(), round.endMs(), items);
        }).toList();
        return new Timeline(timeline.eventId(), timeline.eventName(), timeline.audioFile(), rounds);
    }

    private static void validate(Timeline timeline) {
        if (isBlank(timeline.eventId())) {
            throw new IllegalStateException("编排文件缺少 eventId");
        }
        if (isBlank(timeline.eventName())) {
            throw new IllegalStateException("编排文件缺少 eventName");
        }
        if (isBlank(timeline.audioFile())) {
            throw new IllegalStateException("编排文件缺少 audioFile");
        }
        List<Round> rounds = timeline.rounds();
        if (rounds.isEmpty()) {
            throw new IllegalStateException("编排文件至少需要一个轮次");
        }
        Set<String> roundIds = new HashSet<>();
        for (Round round : rounds) {
            if (isBlank(round.id())) {
                throw new IllegalStateException("存在缺少 id 的轮次");
            }
            if (!roundIds.add(round.id())) {
                throw new IllegalStateException("轮次 id 重复：" + round.id());
            }
            if (round.startMs() >= round.endMs()) {
                throw new IllegalStateException("轮次 " + round.id() + " 的 startMs 必须小于 endMs");
            }
            for (Round other : rounds) {
                if (other == round) {
                    continue;
                }
                if (round.startMs() < other.endMs() && other.startMs() < round.endMs()) {
                    throw new IllegalStateException("轮次区间重叠：" + round.id() + " 与 " + other.id());
                }
            }
            validateItems(round);
        }
    }

    private static void validateItems(Round round) {
        List<TimelineItem> items = round.items();
        if (items.isEmpty()) {
            throw new IllegalStateException("轮次 " + round.id() + " 的 items 为空");
        }
        Set<String> questionIds = new HashSet<>();
        long previousAtMs = Long.MIN_VALUE;
        int pendingLoads = 0;
        int loadCount = 0;
        int nextCount = 0;
        boolean finishDeclared = false;
        for (int index = 0; index < items.size(); index++) {
            TimelineItem item = items.get(index);
            String position = "轮次 " + round.id() + " 第 " + (index + 1) + " 条条目";
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
                    if (item.question() == null || item.questionId() == null || item.questionId().isBlank()) {
                        throw new IllegalStateException(position + " 的 LOAD 缺少有效 questionId");
                    }
                    if (!questionIds.add(item.questionId())) {
                        throw new IllegalStateException(position + " 重复引用题目：" + item.questionId());
                    }
                    validateQuestion(round, item.question());
                    if (item.audioStartMs() == null || item.audioEndMs() == null
                            || item.audioStartMs() >= item.audioEndMs()) {
                        throw new IllegalStateException(position + " 的音频区间必须满足 audioStartMs < audioEndMs");
                    }
                    if (item.audioStartMs() < round.startMs() || item.audioEndMs() > round.endMs()) {
                        throw new IllegalStateException(position + " 的音频区间超出轮次区间");
                    }
                    pendingLoads++;
                    loadCount++;
                }
                case NEXT -> {
                    if (pendingLoads == 0) {
                        throw new IllegalStateException(position + " 的 NEXT 之前缺少尚未消费的 LOAD");
                    }
                    pendingLoads--;
                    nextCount++;
                }
                case FINISH -> finishDeclared = true;
                default -> {
                }
            }
        }
        if (loadCount != nextCount) {
            throw new IllegalStateException("轮次 " + round.id() + " 的 LOAD 数量与 NEXT 数量不一致");
        }
        if (!finishDeclared) {
            throw new IllegalStateException("轮次 " + round.id() + " 缺少 FINISH 条目");
        }
    }

    private static void validateQuestion(Round round, Question question) {
        String position = "轮次 " + round.id() + " 题目 " + question.id();
        if (isBlank(question.audioCode())) {
            throw new IllegalStateException(position + " 缺少 audioCode");
        }
        if (isBlank(question.prompt())) {
            throw new IllegalStateException(position + " 缺少 prompt");
        }
        if (question.type() == null) {
            throw new IllegalStateException(position + " 缺少 type");
        }
        if (question.difficulty() == null) {
            throw new IllegalStateException(position + " 缺少 difficulty");
        }
        if (question.options().size() != REQUIRED_OPTION_COUNT) {
            throw new IllegalStateException(position + " 的 options 必须为 4 项");
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
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
