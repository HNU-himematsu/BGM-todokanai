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
        "app.online-timeline-file=",
        "app.audio-url-online=",
        "app.audio-url=https://cdn.example.com/bgm/event-test.wav",
        "app.leaderboard-file=target/test-data/leaderboards.json",
        "app.online-leaderboard-file=target/test-data/online_leaderboard.json",
        "app.leaderboard-txt-file=target/test-data/leaderboard_summary.txt"
})
@AutoConfigureMockMvc
class ExternalAudioUrlTests {
    private static final String AUDIO_URL = "https://cdn.example.com/bgm/event-test.wav";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void onlineQuestionSetUsesExternalAudioUrl() throws Exception {
        mockMvc.perform(get("/api/events/demo/online/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.audioUrl").value(AUDIO_URL));
    }

    @Test
    void audioEndpointRedirectsToExternalAudioUrl() throws Exception {
        mockMvc.perform(get("/api/events/demo/audio"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, AUDIO_URL));
    }
}
