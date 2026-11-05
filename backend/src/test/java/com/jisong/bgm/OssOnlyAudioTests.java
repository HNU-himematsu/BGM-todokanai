package com.jisong.bgm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisong.bgm.service.TimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class OssOnlyAudioTests {
    private static final String AUDIO_URL =
            "https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-demo.mp3";

    @TempDir
    Path tempDir;

    @Test
    void externalAudioUrlDoesNotRequireLocalAudioFile() throws Exception {
        Files.copy(Path.of("src/test/resources/timeline-test.json"), tempDir.resolve("timeline.json"));
        Files.copy(Path.of("src/test/resources/questions.json"), tempDir.resolve("questions.json"));

        TimelineStore store = new TimelineStore(
                new ObjectMapper(),
                tempDir.resolve("timeline.json").toString(),
                AUDIO_URL
        );

        assertThat(store.audioPath()).doesNotExist();
        assertThat(store.audioUrl()).isEqualTo(AUDIO_URL);
        assertThatCode(store::requireAudioReadable).doesNotThrowAnyException();
    }
}
