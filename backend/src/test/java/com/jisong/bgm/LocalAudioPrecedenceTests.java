package com.jisong.bgm;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.timeline-file=src/test/resources/timeline-test.json",
        "app.online-timeline-file=",
        "app.audio-url-online=",
        "app.audio-url=https://cdn.example.com/bgm/event-test.wav",
        "app.leaderboard-file=target/test-data/local-audio-leaderboards.json",
        "app.online-leaderboard-file=target/test-data/local-audio-online_leaderboard.json",
        "app.leaderboard-txt-file=target/test-data/local-audio-leaderboard_summary.txt"
})
@AutoConfigureMockMvc
class LocalAudioPrecedenceTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void onlineQuestionSetUsesLocalAudioWhenFileIsReadable() throws Exception {
        mockMvc.perform(get("/api/events/demo/online/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.audioUrl").value("/api/events/demo/audio?mode=online"));
    }

    @Test
    void audioEndpointStreamsLocalAudioInsteadOfRedirecting() throws Exception {
        mockMvc.perform(get("/api/events/demo/audio"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    @Test
    void rangeRequestReturnsPartialContentFromLocalAudio() throws Exception {
        mockMvc.perform(get("/api/events/demo/audio").header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, startsWith("bytes 0-1023/")));
    }
}
