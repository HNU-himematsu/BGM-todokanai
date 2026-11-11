package com.jisong.bgm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 线下榜单 JSON 无法写入时，活动保留可重试的揭晓状态，不进入 FINISHED。
 */
@SpringBootTest(properties = {
        "app.timeline-file=src/test/resources/manual/timeline.json",
        "app.online-timeline-file=",
        "app.audio-url-online=",
        "app.audio-url=http://127.0.0.1/unused-audio.mp3",
        "app.leaderboard-file=pom.xml/archive-blocked.json",
        "app.online-leaderboard-file=target/test-data/archive-failure-online.json",
        "app.nickname-claims-file=target/test-data/archive-failure-nickname-claims.json",
        "app.leaderboard-txt-file=target/test-data/archive-failure-summary.txt"
})
@AutoConfigureMockMvc
class ArchiveFailureTests {
    @BeforeEach
    void clearNicknameClaims() throws Exception {
        java.nio.file.Files.deleteIfExists(
                java.nio.file.Path.of("target/test-data/archive-failure-nickname-claims.json"));
    }
    private static final String TOKEN_HEADER = "X-Host-Token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void failedArchiveKeepsRoundRetryable() throws Exception {
        String token = login();
        mockMvc.perform(post("/api/events/demo/host/reset").header(TOKEN_HEADER, token));
        JsonNode joined = data(mockMvc.perform(post("/api/events/demo/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"归档失败\",\"mode\":\"OFFLINE\"}")));
        String playerId = joined.get("playerId").asText();
        mockMvc.perform(post("/api/events/demo/host/start").header(TOKEN_HEADER, token));

        awaitState(playerId, "ANSWER_REVEALED", "t-03", 9000);
        Thread.sleep(1200);

        JsonNode failed = state(playerId);
        assertThat(failed.get("status").asText()).isEqualTo("ANSWER_REVEALED");
        assertThat(failed.get("timelineRunning").asBoolean()).isFalse();

        JsonNode hostState = data(mockMvc.perform(
                get("/api/events/demo/host/state").header(TOKEN_HEADER, token)));
        assertThat(hostState.get("canFinishRound").asBoolean()).isTrue();

        mockMvc.perform(post("/api/events/demo/host/finish").header(TOKEN_HEADER, token))
                .andExpect(status().isInternalServerError());

        assertThat(state(playerId).get("status").asText()).isEqualTo("ANSWER_REVEALED");
    }

    private String login() throws Exception {
        JsonNode data = data(mockMvc.perform(post("/api/host/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"123456\"}")));
        return data.get("token").asText();
    }

    private JsonNode state(String playerId) throws Exception {
        return data(mockMvc.perform(get("/api/events/demo/state").param("playerId", playerId)));
    }

    private void awaitState(
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
                    && expectedQuestionId.equals(latest.get("question").get("id").asText())) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("未在超时前达到状态 " + expectedStatus + "，最后状态：" + latest);
    }

    private JsonNode data(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }
}
