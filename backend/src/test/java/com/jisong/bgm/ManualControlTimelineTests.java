package com.jisong.bgm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 主持人手动兜底动作只影响当前题：后续题目仍按编排完成锁卷、揭晓与上屏。
 */
@SpringBootTest(properties = {
        "app.timeline-file=src/test/resources/manual/timeline.json",
        "app.audio-url=http://127.0.0.1/unused-audio.mp3",
        "app.leaderboard-file=target/test-data/manual-leaderboards.json",
        "app.online-leaderboard-file=target/test-data/manual-online-leaderboards.json",
        "app.leaderboard-txt-file=target/test-data/manual-leaderboard-summary.txt"
})
@AutoConfigureMockMvc
class ManualControlTimelineTests {
    private static final String TOKEN_HEADER = "X-Host-Token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void manualLockOnlySkipsTheCurrentQuestionLock() throws Exception {
        String token = login();
        String playerId = prepareRound(token);

        JsonNode answering = awaitState(playerId, "ANSWERING", "t-01", 2000);
        assertThat(answering.get("canAnswer").asBoolean()).isTrue();

        JsonNode locked = data(post("/api/events/demo/host/lock").header(TOKEN_HEADER, token));
        assertThat(locked.get("status").asText()).isEqualTo("ANSWER_LOCKED");
        assertThat(locked.get("question").get("id").asText()).isEqualTo("t-01");

        awaitState(playerId, "ANSWER_REVEALED", "t-01", 3000);
        awaitState(playerId, "ANSWERING", "t-02", 3000);

        JsonNode secondLock = awaitState(playerId, "ANSWER_LOCKED", "t-02", 3000);
        assertThat(secondLock.get("question").get("answerIndex").isNull()).isTrue();
        assertThat(secondLock.get("canAnswer").asBoolean()).isFalse();

        awaitState(playerId, "ANSWERING", "t-03", 3000);
        awaitState(playerId, "FINISHED", "t-03", 5000);
        awaitTimelineStopped(playerId, 3000);
    }

    @Test
    void manualRevealOnlySkipsTheCurrentQuestionReveal() throws Exception {
        String token = login();
        String playerId = prepareRound(token);

        awaitState(playerId, "ANSWER_LOCKED", "t-01", 3000);

        JsonNode revealed = data(post("/api/events/demo/host/reveal").header(TOKEN_HEADER, token));
        assertThat(revealed.get("status").asText()).isEqualTo("ANSWER_REVEALED");
        assertThat(revealed.get("question").get("id").asText()).isEqualTo("t-01");
        assertThat(revealed.get("question").get("answerIndex").asInt()).isEqualTo(1);

        awaitState(playerId, "ANSWERING", "t-02", 3000);
        awaitState(playerId, "ANSWER_LOCKED", "t-02", 3000);
        awaitState(playerId, "ANSWER_REVEALED", "t-02", 3000);
        awaitState(playerId, "ANSWERING", "t-03", 3000);
        awaitState(playerId, "FINISHED", "t-03", 5000);
        awaitTimelineStopped(playerId, 3000);
    }

    private String prepareRound(String token) throws Exception {
        perform(post("/api/events/demo/host/reset").header(TOKEN_HEADER, token));
        JsonNode joined = data(post("/api/events/demo/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"手动兜底\",\"mode\":\"OFFLINE\"}"));
        perform(post("/api/events/demo/host/start").header(TOKEN_HEADER, token));
        return joined.get("playerId").asText();
    }

    private String login() throws Exception {
        JsonNode data = data(post("/api/host/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"123456\"}"));
        return data.get("token").asText();
    }

    private JsonNode state(String playerId) throws Exception {
        return data(get("/api/events/demo/state").param("playerId", playerId));
    }

    private JsonNode awaitState(
            String playerId,
            String expectedStatus,
            String expectedQuestionId,
            long timeoutMs
    ) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonNode latest = null;
        while (System.currentTimeMillis() < deadline) {
            latest = state(playerId);
            if (expectedStatus.equals(latest.get("status").asText())
                    && expectedQuestionId.equals(questionId(latest))) {
                return latest;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("等待状态超时：" + expectedStatus + " / " + expectedQuestionId
                + "，当前为 " + (latest == null
                ? "未知"
                : latest.get("status").asText() + " / " + questionId(latest)));
    }

    private void awaitTimelineStopped(String playerId, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!state(playerId).get("timelineRunning").asBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("等待时间轴停止超时");
    }

    private static String questionId(JsonNode state) {
        JsonNode question = state.get("question");
        return question == null || question.isNull() ? "无题目" : question.get("id").asText();
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");
    }

    private void perform(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request).andExpect(status().isOk());
    }
}
