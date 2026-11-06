package com.jisong.bgm;

import com.jisong.bgm.controller.EventController;
import com.jisong.bgm.service.ActivityService;
import com.jisong.bgm.service.OnlineAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventControllerAudioRangeTests {
    private static final int AUDIO_BYTES = 2 * 1024 * 1024;

    @TempDir
    Path tempDir;

    private ActivityService activityService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        activityService = mock(ActivityService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EventController(activityService, mock(OnlineAttemptService.class)))
                .build();
    }

    @Test
    void openEndedRangeReturnsWholeRemainingRegion() throws Exception {
        when(activityService.audioPath("demo")).thenReturn(audioFile());

        mockMvc.perform(get("/api/events/demo/audio").header(HttpHeaders.RANGE, "bytes=0-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE,
                        "bytes 0-" + (AUDIO_BYTES - 1) + "/" + AUDIO_BYTES))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(AUDIO_BYTES)));
    }

    @Test
    void boundedRangeReturnsExactRequestedRegion() throws Exception {
        when(activityService.audioPath("demo")).thenReturn(audioFile());

        mockMvc.perform(get("/api/events/demo/audio").header(HttpHeaders.RANGE, "bytes=1000-1999"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE,
                        "bytes 1000-1999/" + AUDIO_BYTES))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "1000"));
    }

    @Test
    void onlineModeStreamsOnlineAudioWithRange() throws Exception {
        when(activityService.onlineAudioPath("demo")).thenReturn(audioFile());

        mockMvc.perform(get("/api/events/demo/audio")
                        .param("mode", "online")
                        .header(HttpHeaders.RANGE, "bytes=2000-2999"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE,
                        "bytes 2000-2999/" + AUDIO_BYTES))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "1000"));
    }

    private Path audioFile() throws Exception {
        Path audio = tempDir.resolve("event-demo.mp3");
        Files.write(audio, new byte[AUDIO_BYTES]);
        return audio;
    }
}
