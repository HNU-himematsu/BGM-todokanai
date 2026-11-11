package com.jisong.bgm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.timeline-file=src/test/resources/timeline-test.json",
        "app.online-timeline-file=",
        "app.audio-url-online=",
        "app.audio-url=",
        "app.leaderboard-file=target/test-data/leaderboards.json",
        "app.online-leaderboard-file=target/test-data/online_leaderboard.json",
        "app.nickname-claims-file=target/test-data/online-nickname-claims.json",
        "app.leaderboard-txt-file=target/test-data/leaderboard_summary.txt"
})
@AutoConfigureMockMvc
class OnlineFlowTests {
    @BeforeEach
    void clearNicknameClaims() throws Exception {
        Files.deleteIfExists(Path.of("target/test-data/online-nickname-claims.json"));
    }
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void defaultJoinUsesOnlineModeWithoutOfflineSession() throws Exception {
        JsonNode joined = data(post("/api/events/demo/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"线上甲\"}"));

        assertThat(joined.get("mode").asText()).isEqualTo("ONLINE");
        assertThat(joined.get("participantId").asText()).isNotBlank();
        assertThat(joined.get("playerId").isNull()).isTrue();
        assertThat(joined.get("eventName").asText()).isEqualTo("编排联调演示");
        assertThat(joined.get("onlineLocked").asBoolean()).isFalse();
    }

    @Test
    void joinValidatesNicknameAfterTrimming() throws Exception {
        JsonNode joined = data(post("/api/events/demo/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\" 12345678901234567890 \"}"));

        assertThat(joined.get("nickname").asText()).isEqualTo("12345678901234567890");
    }

    @Test
    void questionSetIsSanitizedAndVersioned() throws Exception {
        JsonNode questionSet = questionSet();

        assertThat(questionSet.get("eventId").asText()).isEqualTo("demo");
        assertThat(questionSet.get("audioUrl").asText()).isEqualTo("/api/events/demo/audio?mode=online");
        assertThat(questionSet.get("audioStartMs").asLong()).isZero();
        assertThat(questionSet.get("audioEndMs").asLong()).isEqualTo(3600L);
        assertThat(questionSet.get("totalQuestions").asInt()).isEqualTo(2);
        assertThat(questionSet.get("questionSetVersion").asText()).hasSize(64)
                .matches("[0-9a-f]{64}");

        JsonNode questions = questionSet.get("questions");
        assertThat(questions).hasSize(2);
        assertThat(questions.get(0).get("id").asText()).isEqualTo("t-01");
        assertThat(questions.get(0).get("audioStartMs").asLong()).isZero();
        assertThat(questions.get(0).get("audioEndMs").asLong()).isEqualTo(2000L);
        assertThat(questions.get(0).get("answerStartMs").asLong()).isEqualTo(100L);
        assertThat(questions.get(0).get("answerEndMs").asLong()).isEqualTo(1500L);
        assertThat(questions.get(0).get("author").asText()).isEqualTo("联调出题人甲");
        assertThat(questions.get(1).get("id").asText()).isEqualTo("t-02");
        assertThat(questions.get(1).get("audioStartMs").asLong()).isEqualTo(2000L);
        for (JsonNode question : questions) {
            assertThat(question.has("correctOption")).isFalse();
            assertThat(question.has("workName")).isFalse();
            assertThat(question.has("explanation")).isFalse();
        }
    }

    @Test
    void previewScoresWithoutPersistingAndValidatesAnswers() throws Exception {
        String participantId = UUID.randomUUID().toString();
        String attemptId = UUID.randomUUID().toString();
        String version = questionSet().get("questionSetVersion").asText();

        JsonNode preview = data(post("/api/events/demo/online/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(submission(participantId, "预览玩家", attemptId, version, 68210L,
                        new int[][]{{0, 1}, {1, 1}})));

        assertThat(preview.get("score").asInt()).isEqualTo(2);
        assertThat(preview.get("answeredCount").asInt()).isEqualTo(2);
        assertThat(preview.get("correctCount").asInt()).isEqualTo(1);
        assertThat(preview.get("wrongCount").asInt()).isEqualTo(1);
        assertThat(preview.get("totalQuestions").asInt()).isEqualTo(2);
        assertThat(preview.get("durationMs").asLong()).isEqualTo(68210L);
        assertThat(preview.get("questionSetVersion").asText()).isEqualTo(version);

        JsonNode status = onlineStatus(participantId);
        assertThat(status.get("status").asText()).isEqualTo("NOT_LOCKED");
        assertThat(status.get("score").isNull()).isTrue();

        mockMvc.perform(post("/api/events/demo/online/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission(participantId, "预览玩家", attemptId, version, 10L,
                                new int[][]{{9, 1}})))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/events/demo/online/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission(participantId, "预览玩家", attemptId, version, 10L,
                                new int[][]{{0, 1}, {0, 0}})))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/events/demo/online/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission(participantId, "预览玩家", attemptId, version, 10L,
                                new int[][]{{0, 4}})))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/events/demo/online/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission(participantId, "预览玩家", attemptId, "stale-version", 10L,
                                new int[][]{{0, 1}})))
                .andExpect(status().isConflict());
    }

    @Test
    void lockIsIdempotentAndUpdatesOnlineAndTotalLeaderboards() throws Exception {
        String participantId = UUID.randomUUID().toString();
        String attemptId = UUID.randomUUID().toString();
        String version = questionSet().get("questionSetVersion").asText();
        String body = submission(participantId, "锁定玩家", attemptId, version, 32100L,
                new int[][]{{0, 1}, {1, 0}});

        JsonNode locked = data(post("/api/events/demo/online/lock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
        assertThat(locked.get("attemptId").asText()).isEqualTo(attemptId);
        assertThat(locked.get("participantId").asText()).isEqualTo(participantId);
        assertThat(locked.get("score").asInt()).isEqualTo(7);
        assertThat(locked.get("correctCount").asInt()).isEqualTo(2);
        assertThat(locked.get("wrongCount").asInt()).isZero();
        assertThat(locked.get("onlineRank").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(locked.get("totalRank").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(locked.get("lockedAt").asText()).isNotBlank();

        JsonNode repeated = data(post("/api/events/demo/online/lock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(submission(participantId, "锁定玩家", attemptId, "stale-version", 1L,
                        new int[][]{})));
        assertThat(repeated.get("lockedAt").asText()).isEqualTo(locked.get("lockedAt").asText());
        assertThat(repeated.get("score").asInt()).isEqualTo(7);

        mockMvc.perform(post("/api/events/demo/online/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission(participantId, "锁定玩家", UUID.randomUUID().toString(),
                                version, 100L, new int[][]{{0, 1}})))
                .andExpect(status().isConflict());

        JsonNode status = onlineStatus(participantId);
        assertThat(status.get("status").asText()).isEqualTo("LOCKED");
        assertThat(status.get("score").asInt()).isEqualTo(7);
        assertThat(status.get("answeredCount").asInt()).isEqualTo(2);
        assertThat(status.get("correctCount").asInt()).isEqualTo(2);
        assertThat(status.get("wrongCount").asInt()).isZero();
        assertThat(status.get("totalQuestions").asInt()).isEqualTo(2);

        JsonNode onlineLeaderboard = data(get("/api/events/demo/leaderboards/online"));
        JsonNode onlineEntry = findByParticipant(onlineLeaderboard.get("entries"), participantId);
        assertThat(onlineEntry).isNotNull();
        assertThat(onlineEntry.get("score").asInt()).isEqualTo(7);
        assertThat(onlineEntry.get("durationMs").asLong()).isEqualTo(32100L);

        JsonNode totalLeaderboard = data(get("/api/events/demo/leaderboards/total"));
        JsonNode totalEntry = findByParticipant(totalLeaderboard.get("entries"), participantId);
        assertThat(totalEntry).isNotNull();
        assertThat(totalEntry.get("totalScore").asInt()).isEqualTo(7);
        assertThat(totalEntry.get("onlineScore").asInt()).isEqualTo(7);
        assertThat(totalEntry.get("offlineRoundCount").asInt()).isZero();
        assertThat(totalEntry.get("onlineLocked").asBoolean()).isTrue();

        JsonNode joined = data(post("/api/events/demo/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"锁定玩家\",\"mode\":\"ONLINE\",\"participantId\":\""
                        + participantId + "\"}"));
        assertThat(joined.get("onlineLocked").asBoolean()).isTrue();
        assertThat(joined.get("onlineScore").asInt()).isEqualTo(7);
    }

    @Test
    void unknownEventAndModeReturnDocumentedErrors() throws Exception {
        mockMvc.perform(get("/api/events/missing/online/questions"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/events/demo/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"模式玩家\",\"mode\":\"HYBRID\"}"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode questionSet() throws Exception {
        return data(get("/api/events/demo/online/questions"));
    }

    private JsonNode onlineStatus(String participantId) throws Exception {
        return data(get("/api/events/demo/online/status").param("participantId", participantId));
    }

    private JsonNode findByParticipant(JsonNode entries, String participantId) {
        for (JsonNode entry : entries) {
            if (participantId.equals(entry.get("participantId").asText())) {
                return entry;
            }
        }
        return null;
    }

    private String submission(
            String participantId,
            String nickname,
            String attemptId,
            String version,
            long durationMs,
            int[][] answers
    ) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("participantId", participantId);
        root.put("nickname", nickname);
        root.put("attemptId", attemptId);
        root.put("questionSetVersion", version);
        root.put("durationMs", durationMs);
        ArrayNode answerArray = root.putArray("answers");
        for (int[] answer : answers) {
            ObjectNode item = answerArray.addObject();
            item.put("questionId", "t-0" + (answer[0] + 1));
            item.put("optionIndex", answer[1]);
        }
        return objectMapper.writeValueAsString(root);
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }
}
