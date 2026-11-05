package com.jisong.bgm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.timeline-file=src/test/resources/timeline-test.json",
        "app.audio-url=",
        "app.leaderboard-file=target/test-data/leaderboards.json",
        "app.online-leaderboard-file=target/test-data/online_leaderboard.json",
        "app.leaderboard-txt-file=target/test-data/leaderboard_summary.txt"
})
@AutoConfigureMockMvc
class ActivityFlowTests {
    private static final String TOKEN_HEADER = "X-Host-Token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void hostEndpointsRejectMissingToken() throws Exception {
        mockMvc.perform(get("/api/events/demo/host/rounds"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/events/demo/host/state").header(TOKEN_HEADER, "not-a-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/events/demo/host/start").header(TOKEN_HEADER, "not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void hostLoginRejectsWrongPassword() throws Exception {
        mockMvc.perform(post("/api/host/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void audioEndpointServesFullStreamAndRangeRequests() throws Exception {
        mockMvc.perform(get("/api/events/demo/audio"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, Matchers.startsWith("audio/")));

        mockMvc.perform(get("/api/events/demo/audio").header(HttpHeaders.RANGE, "bytes=0-99"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, Matchers.startsWith("bytes 0-99/")));

        mockMvc.perform(get("/api/events/demo/audio").header(HttpHeaders.RANGE, "bytes=999999999-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, Matchers.startsWith("bytes */")));
    }

    @Test
    void timelineDrivesQuestionLifecycleAndScoring() throws Exception {
        String token = login();

        JsonNode rounds = data(get("/api/events/demo/host/rounds").header(TOKEN_HEADER, token));
        assertThat(rounds.get("currentRoundId").asText()).isEqualTo("round-1");
        assertThat(rounds.get("rounds")).hasSize(2);
        assertThat(rounds.get("rounds").get(0).get("totalQuestions").asInt()).isEqualTo(2);

        mockMvc.perform(post("/api/events/demo/host/lock").header(TOKEN_HEADER, token))
                .andExpect(status().isBadRequest());

        String playerId = join("甲");
        JsonNode waiting = state(playerId);
        assertThat(waiting.get("status").asText()).isEqualTo("WAITING");
        assertThat(waiting.get("currentQuestionIndex").asInt()).isEqualTo(-1);
        assertThat(waiting.get("timelineRunning").asBoolean()).isFalse();
        assertThat(waiting.get("totalQuestions").asInt()).isEqualTo(2);
        assertThat(waiting.get("question").isNull()).isTrue();

        mockMvc.perform(post("/api/events/demo/host/start").header(TOKEN_HEADER, token))
                .andExpect(status().isOk());

        JsonNode answering = awaitStatus(playerId, "ANSWERING", 4000);
        assertThat(answering.get("timelineRunning").asBoolean()).isTrue();
        assertThat(answering.get("question").get("id").asText()).isEqualTo("t-01");
        assertThat(answering.get("question").get("answerIndex").isNull()).isTrue();
        assertThat(answering.get("question").get("analysis").isNull()).isTrue();
        assertThat(answering.get("question").get("workName").isNull()).isTrue();
        assertThat(answering.get("question").get("author").isNull()).isTrue();
        assertThat(answering.get("canAnswer").asBoolean()).isTrue();
        long audioStart = answering.get("questionAudioStartEpochMs").asLong();
        long audioEnd = answering.get("questionAudioEndEpochMs").asLong();
        assertThat(audioEnd - audioStart).isEqualTo(2000L);

        String latePlayerId = join("乙");
        assertThat(state(latePlayerId).get("canAnswer").asBoolean()).isTrue();

        perform(post("/api/events/demo/answers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"playerId\":\"" + playerId + "\",\"questionId\":\"t-01\",\"optionIndex\":1}"));

        JsonNode hostState = data(get("/api/events/demo/host/state").header(TOKEN_HEADER, token));
        assertThat(hostState.get("players")).hasSize(2);
        assertThat(hostState.get("state").get("answeredCount").asInt()).isEqualTo(1);
        assertThat(hostState.get("state").get("question").get("workName").asText()).isEqualTo("联调作品甲");
        assertThat(hostState.get("state").get("question").get("author").asText()).isEqualTo("联调出题人甲");
        assertThat(hostState.get("rounds")).hasSize(2);

        JsonNode paused = data(post("/api/events/demo/host/pause").header(TOKEN_HEADER, token));
        assertThat(paused.get("timelineRunning").asBoolean()).isFalse();
        assertThat(paused.get("status").asText()).isEqualTo("ANSWERING");
        Thread.sleep(300);
        perform(post("/api/events/demo/host/lock").header(TOKEN_HEADER, token));
        JsonNode locked = state(playerId);
        assertThat(locked.get("status").asText()).isEqualTo("ANSWER_LOCKED");
        assertThat(locked.get("question").get("answerIndex").isNull()).isTrue();
        assertThat(locked.get("canAnswer").asBoolean()).isFalse();

        perform(post("/api/events/demo/host/reveal").header(TOKEN_HEADER, token));
        JsonNode revealed = state(playerId);
        assertThat(revealed.get("status").asText()).isEqualTo("ANSWER_REVEALED");
        assertThat(revealed.get("question").get("answerIndex").asInt()).isEqualTo(1);
        assertThat(revealed.get("question").get("analysis").asText()).isEqualTo("联调第一题解析");
        assertThat(revealed.get("question").get("author").asText()).isEqualTo("联调出题人甲");

        JsonNode revealSnapshot = data(get("/api/events/demo/host/state").header(TOKEN_HEADER, token));
        assertThat(revealSnapshot.get("canAdvanceQuestion").asBoolean()).isTrue();
        JsonNode advanced = data(post("/api/events/demo/host/next").header(TOKEN_HEADER, token));
        assertThat(advanced.get("status").asText()).isEqualTo("ANSWERING");
        assertThat(advanced.get("currentQuestionIndex").asInt()).isEqualTo(1);
        assertThat(advanced.get("question").get("id").asText()).isEqualTo("t-02");
        assertThat(advanced.get("audioPositionMs").asLong()).isEqualTo(2000L);

        JsonNode resumed = data(post("/api/events/demo/host/resume").header(TOKEN_HEADER, token));
        assertThat(resumed.get("timelineRunning").asBoolean()).isTrue();
        assertThat(resumed.get("status").asText()).isEqualTo("ANSWERING");
        assertThat(resumed.get("question").get("id").asText()).isEqualTo("t-02");

        JsonNode finished = awaitStatus(playerId, "FINISHED", 6000);
        assertThat(finished.get("playerScore").asInt()).isEqualTo(10);
        assertThat(finished.get("playerRank").asInt()).isEqualTo(1);
        assertThat(finished.get("playerDurationMs").asLong()).isGreaterThan(0L);
        assertThat(finished.get("leaderboard")).hasSize(2);

        JsonNode leaderboard = data(get("/api/events/demo/leaderboard"));
        assertThat(leaderboard).hasSize(2);
        assertThat(leaderboard.get(0).get("nickname").asText()).isEqualTo("甲");
        assertThat(leaderboard.get(0).get("score").asInt()).isEqualTo(10);

        mockMvc.perform(post("/api/events/demo/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"丙\",\"mode\":\"OFFLINE\"}"))
                .andExpect(status().isBadRequest());

        awaitTimelineStopped(playerId, 5000);
        JsonNode switched = data(post("/api/events/demo/host/round")
                .header(TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roundId\":\"round-2\"}"));
        assertThat(switched.get("status").asText()).isEqualTo("WAITING");
        assertThat(switched.get("currentQuestionIndex").asInt()).isEqualTo(-1);
        assertThat(switched.get("playerCount").asInt()).isZero();

        JsonNode roundsAfterSwitch = data(get("/api/events/demo/host/rounds").header(TOKEN_HEADER, token));
        assertThat(roundsAfterSwitch.get("currentRoundId").asText()).isEqualTo("round-2");

        perform(post("/api/events/demo/host/start").header(TOKEN_HEADER, token));
    }

    private String login() throws Exception {
        JsonNode data = data(post("/api/host/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"123456\"}"));
        return data.get("token").asText();
    }

    private String join(String nickname) throws Exception {
        JsonNode data = data(post("/api/events/demo/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname + "\",\"mode\":\"OFFLINE\"}"));
        return data.get("playerId").asText();
    }

    private JsonNode state(String playerId) throws Exception {
        return data(get("/api/events/demo/state").param("playerId", playerId));
    }

    private JsonNode awaitStatus(String playerId, String expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonNode latest = null;
        while (System.currentTimeMillis() < deadline) {
            latest = state(playerId);
            if (expected.equals(latest.get("status").asText())) {
                return latest;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("等待活动状态超时：" + expected
                + "，当前为 " + (latest == null ? "未知" : latest.get("status").asText()));
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

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private void perform(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request).andExpect(status().isOk());
    }
}
