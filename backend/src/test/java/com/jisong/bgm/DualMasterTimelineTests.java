package com.jisong.bgm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jisong.bgm.model.OnlineQuestionWindow;
import com.jisong.bgm.service.ResourceNotFoundException;
import com.jisong.bgm.service.TimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DualMasterTimelineTests {
    private static final Path TEST_RESOURCES = Path.of("src/test/resources");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void onlineQuestionSetUsesOnlineTimelineWindows() throws Exception {
        installBaseline();
        installOnlineTimeline(root -> { });

        TimelineStore store = newStore("", "");

        assertThat(store.timeline().rounds().get(0).items().get(0).audioEndMs()).isEqualTo(2000L);
        assertThat(store.onlineTimeline().rounds().get(0).items().get(0).audioEndMs()).isEqualTo(1000L);
        assertThat(store.onlineQuestions()).containsExactly(
                new OnlineQuestionWindow("t-01", 0L, 1000L, 10L, 800L),
                new OnlineQuestionWindow("t-02", 1000L, 3000L, 1100L, 2800L));
    }

    @Test
    void blankOnlineTimelineUsesOfflineTimeline() throws Exception {
        installBaseline();

        TimelineStore store = new TimelineStore(
                objectMapper, timelinePath().toString(), "", "", "");

        assertThat(store.onlineTimeline()).isSameAs(store.timeline());
        assertThat(store.onlineQuestions()).containsExactly(
                new OnlineQuestionWindow("t-01", 0L, 2000L, 100L, 1500L),
                new OnlineQuestionWindow("t-02", 2000L, 3600L, 2100L, 3200L));
        assertThat(store.onlineAudioUrl()).isEqualTo("/api/events/demo/audio?mode=online");
    }

    @Test
    void onlineAudioUrlPrefersOnlineThenFallsBackToOffline() throws Exception {
        installBaseline();

        TimelineStore preferred = new TimelineStore(
                objectMapper, timelinePath().toString(), "",
                "https://cdn.example.com/offline.mp3",
                "https://cdn.example.com/online.mp3");
        TimelineStore fallback = new TimelineStore(
                objectMapper, timelinePath().toString(), "",
                "https://cdn.example.com/offline.mp3",
                "");
        TimelineStore local = new TimelineStore(
                objectMapper, timelinePath().toString(), "", "", "");

        assertThat(preferred.onlineAudioUrl()).isEqualTo("https://cdn.example.com/online.mp3");
        assertThat(fallback.onlineAudioUrl()).isEqualTo("https://cdn.example.com/offline.mp3");
        assertThat(local.onlineAudioUrl()).isEqualTo("/api/events/demo/audio?mode=online");
    }

    @Test
    void crossTimelineEventIdMismatchFailsStartup() throws Exception {
        installBaseline();
        installOnlineTimeline(root -> root.put("eventId", "other"));

        assertThatThrownBy(() -> newStore("", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eventId 不一致");
    }

    @Test
    void crossTimelineEventNameMismatchFailsStartup() throws Exception {
        installBaseline();
        installOnlineTimeline(root -> root.put("eventName", "另一个活动"));

        assertThatThrownBy(() -> newStore("", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eventName 不一致");
    }

    @Test
    void crossTimelineQuestionOrderMismatchFailsStartup() throws Exception {
        installBaseline();
        installOnlineTimeline(root -> {
            ArrayNode items = (ArrayNode) root.path("rounds").get(0).path("items");
            for (JsonNode item : items) {
                if ("LOAD".equals(item.path("action").asText())) {
                    ObjectNode load = (ObjectNode) item;
                    String questionId = load.path("questionId").asText();
                    load.put("questionId", "t-01".equals(questionId) ? "t-02" : "t-01");
                }
            }
        });

        assertThatThrownBy(() -> newStore("", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("题目顺序不一致");
    }

    @Test
    void questionBankCoverageMismatchFailsStartup() throws Exception {
        installBaseline();
        installOnlineTimeline(root -> { });
        ArrayNode questions = (ArrayNode) objectMapper.readTree(
                Files.readAllBytes(tempDir.resolve("questions.json")));
        ObjectNode extra = questions.addObject();
        extra.setAll((ObjectNode) questions.get(0));
        extra.put("id", "t-03");
        objectMapper.writeValue(tempDir.resolve("questions.json").toFile(), questions);

        assertThatThrownBy(() -> newStore("", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("题库题目未被线上题集覆盖：[t-03]");
    }

    @Test
    void missingOnlineLocalAudioFailsReadabilityCheck() throws Exception {
        installBaseline();
        installOnlineTimeline(root -> root.put("audioFile", "missing-online.mp3"));

        TimelineStore store = newStore("", "");

        assertThatThrownBy(store::requireOnlineAudioReadable)
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("线上音频文件不可读");
    }

    private TimelineStore newStore(String audioUrl, String onlineAudioUrl) {
        return new TimelineStore(
                objectMapper,
                timelinePath().toString(),
                tempDir.resolve("timeline-online.json").toString(),
                audioUrl,
                onlineAudioUrl);
    }

    private void installBaseline() throws Exception {
        Files.copy(TEST_RESOURCES.resolve("timeline-test.json"), timelinePath());
        Files.copy(TEST_RESOURCES.resolve("questions.json"), tempDir.resolve("questions.json"));
    }

    private void installOnlineTimeline(Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode root = (ObjectNode) objectMapper.readTree(
                Files.readAllBytes(TEST_RESOURCES.resolve("timeline-online-test.json")));
        mutation.accept(root);
        objectMapper.writeValue(tempDir.resolve("timeline-online.json").toFile(), root);
    }

    private Path timelinePath() {
        return tempDir.resolve("timeline.json");
    }
}
