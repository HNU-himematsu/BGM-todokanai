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

    @Autowired
    private MockMvc mockMvc;

    @Test
    void onlineQuestionSetUsesLocalOnlineAudioAndWindows() throws Exception {
        mockMvc.perform(get("/api/events/demo/online/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.audioUrl").value("/api/events/demo/audio?mode=online"))
                .andExpect(jsonPath("$.data.audioStartMs").value(0))
                .andExpect(jsonPath("$.data.audioEndMs").value(3000))
                .andExpect(jsonPath("$.data.questions[0].answerStartMs").value(10))
                .andExpect(jsonPath("$.data.questions[0].answerEndMs").value(800));
    }

    @Test
    void offlineAudioEndpointStreamsLocalAudio() throws Exception {
        mockMvc.perform(get("/api/events/demo/audio"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    @Test
    void onlineAudioEndpointStreamsLocalOnlineAudio() throws Exception {
        mockMvc.perform(get("/api/events/demo/audio").param("mode", "online"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    @Test
    void onlineModeRangeRequestReturnsPartialContent() throws Exception {
        mockMvc.perform(get("/api/events/demo/audio")
                        .param("mode", "online")
                        .header(HttpHeaders.RANGE, "bytes=1024-2047"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 1024-2047/128044"));
    }

    @Test
    void unknownAudioModeFallsBackToLocalOfflineAudio() throws Exception {
        mockMvc.perform(get("/api/events/demo/audio").param("mode", "hybrid"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }
}
