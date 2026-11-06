package com.jisong.bgm;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.timeline-file=src/test/resources/timeline-test.json",
        "app.online-timeline-file=src/test/resources/timeline-online-test.json",
        "app.audio-url=https://cdn.example.com/bgm/event-offline.wav",
        "app.audio-url-online=https://cdn.example.com/bgm/event-online.wav",
        "app.leaderboard-file=target/test-data/dual-leaderboards.json",
        "app.online-leaderboard-file=target/test-data/dual-online-leaderboards.json",
        "app.leaderboard-txt-file=target/test-data/dual-leaderboard-summary.txt"
})
@AutoConfigureMockMvc
class DualMasterAudioEndpointTests {
    private static final String OFFLINE_URL = "https://cdn.example.com/bgm/event-offline.wav";
    private static final String ONLINE_URL = "https://cdn.example.com/bgm/event-online.wav";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void onlineQuestionSetUsesOnlineAudioAndWindows() throws Exception {
        mockMvc.perform(get("/api/events/demo/online/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.audioUrl").value(ONLINE_URL))
                .andExpect(jsonPath("$.data.audioStartMs").value(0))
                .andExpect(jsonPath("$.data.audioEndMs").value(3000))
                .andExpect(jsonPath("$.data.questions[0].answerStartMs").value(10))
                .andExpect(jsonPath("$.data.questions[0].answerEndMs").value(800));
    }

    @Test
    void offlineAudioEndpointRedirectsToOfflineObject() throws Exception {
        mockMvc.perform(get("/api/events/demo/audio"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, OFFLINE_URL));
    }

    @Test
    void onlineAudioEndpointRedirectsToOnlineObject() throws Exception {
        mockMvc.perform(get("/api/events/demo/audio").param("mode", "online"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, ONLINE_URL));
    }

    @Test
    void unknownAudioModeFallsBackToOfflineObject() throws Exception {
        mockMvc.perform(get("/api/events/demo/audio").param("mode", "hybrid"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, OFFLINE_URL));
    }
}
