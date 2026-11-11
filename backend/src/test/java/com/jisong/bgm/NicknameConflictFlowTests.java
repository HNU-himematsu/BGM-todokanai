package com.jisong.bgm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
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
        "app.leaderboard-file=target/test-data/nickname-flow-leaderboards.json",
        "app.online-leaderboard-file=target/test-data/nickname-flow-online.json",
        "app.nickname-claims-file=target/test-data/nickname-flow-claims.json",
        "app.leaderboard-txt-file=target/test-data/nickname-flow-summary.txt"
})
@AutoConfigureMockMvc
class NicknameConflictFlowTests {
    private static final String TOKEN_HEADER = "X-Host-Token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearClaims() throws Exception {
        Files.deleteIfExists(Path.of("target/test-data/nickname-flow-claims.json"));
    }

    @Test
    void ownerKeepsNicknameAcrossRoundsAndOtherIdentityIsRejected() throws Exception {
        MvcResult first = join("aki", "p1");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        String firstPlayerId = data(first).get("playerId").asText();

        MvcResult sameRound = join("aki", "p1");
        assertThat(data(sameRound).get("playerId").asText()).isEqualTo(firstPlayerId);

        String token = login();
        mockMvc.perform(post("/api/events/demo/host/reset").header(TOKEN_HEADER, token))
                .andExpect(status().isOk());

        MvcResult nextRound = join("aki", "p1");
        assertThat(nextRound.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(nextRound).get("playerId").asText()).isNotEqualTo(firstPlayerId);

        mockMvc.perform(post("/api/events/demo/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"aki\",\"mode\":\"OFFLINE\",\"participantId\":\"p2\"}"))
                .andExpect(status().isConflict());

        JsonNode conflicts = data(mockMvc.perform(get("/api/events/demo/host/nickname-conflicts")
                        .header(TOKEN_HEADER, token))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).get("participantId").asText()).isEqualTo("p2");

        JsonNode claims = data(mockMvc.perform(get("/api/events/demo/host/nickname-claims")
                        .header(TOKEN_HEADER, token))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(claims).hasSize(1);
        String claimId = claims.get(0).get("claimId").asText();

        mockMvc.perform(post("/api/events/demo/host/nickname-claims/" + claimId + "/rebind")
                        .header(TOKEN_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantId\":\"p2\"}"))
                .andExpect(status().isOk());

        assertThat(join("aki", "p2").getResponse().getStatus()).isEqualTo(200);
        mockMvc.perform(post("/api/events/demo/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"aki\",\"mode\":\"OFFLINE\",\"participantId\":\"p1\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void releaseAllowsAnotherIdentityToClaimTheNickname() throws Exception {
        join("aki", "p1");
        String token = login();
        JsonNode claims = data(mockMvc.perform(get("/api/events/demo/host/nickname-claims")
                        .header(TOKEN_HEADER, token))
                .andExpect(status().isOk())
                .andReturn());
        String claimId = claims.get(0).get("claimId").asText();

        mockMvc.perform(post("/api/events/demo/host/nickname-claims/" + claimId + "/release")
                        .header(TOKEN_HEADER, token))
                .andExpect(status().isOk());

        assertThat(join("aki", "p2").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void normalizedNicknameCollidesWithExistingClaim() throws Exception {
        join("ＡＫＩ", "p1");

        mockMvc.perform(post("/api/events/demo/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"  aki  \",\"mode\":\"ONLINE\",\"participantId\":\"p2\"}"))
                .andExpect(status().isConflict());
    }

    private MvcResult join(String nickname, String participantId) throws Exception {
        return mockMvc.perform(post("/api/events/demo/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\",\"mode\":\"OFFLINE\",\"participantId\":\""
                                + participantId + "\"}"))
                .andReturn();
    }

    private String login() throws Exception {
        JsonNode token = data(mockMvc.perform(post("/api/host/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn());
        return token.get("token").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");
    }
}
