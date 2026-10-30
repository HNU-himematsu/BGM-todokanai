package com.jisong.bgm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisong.bgm.dto.TotalLeaderboardEntry;
import com.jisong.bgm.service.LeaderboardStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderboardStoreCompatibilityTests {
    @TempDir
    Path temporaryDirectory;

    @Test
    void legacyOfflineSnapshotsRemainReadableAndAggregateIntoTotalLeaderboard() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        LeaderboardStore store = new LeaderboardStore(
                objectMapper,
                "src/test/resources/leaderboards-legacy.json",
                temporaryDirectory.resolve("online_leaderboard.json").toString(),
                temporaryDirectory.resolve("leaderboard_summary.txt").toString());

        List<TotalLeaderboardEntry> entries = store.totalLeaderboard("demo");

        assertThat(entries).isNotEmpty();
        assertThat(entries.stream()
                .flatMap(entry -> entry.offlineContributions().stream())
                .anyMatch(contribution -> contribution.roundId().startsWith("legacy-round-")))
                .isTrue();
        String summary = store.getSummaryTxt();
        assertThat(summary).contains("【线下轮次榜", "【线上积分榜】", "【总榜】");
    }

    @Test
    void historyIsReturnedInFinishedTimeOrder() throws Exception {
        Path offline = temporaryDirectory.resolve("leaderboards.json");
        Files.writeString(offline, """
                [
                  {
                    "eventId": "demo",
                    "eventName": "排序校验",
                    "roundId": "round-2",
                    "roundName": "第二轮",
                    "finishedAt": "2026-10-20T10:00:00Z",
                    "entries": []
                  },
                  {
                    "eventId": "demo",
                    "eventName": "排序校验",
                    "roundId": "round-1",
                    "roundName": "第一轮",
                    "finishedAt": "2026-10-20T09:00:00Z",
                    "entries": []
                  }
                ]
                """, StandardCharsets.UTF_8);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        LeaderboardStore store = new LeaderboardStore(
                objectMapper,
                offline.toString(),
                temporaryDirectory.resolve("online_leaderboard.json").toString(),
                temporaryDirectory.resolve("leaderboard_summary.txt").toString());

        assertThat(store.history("demo"))
                .extracting(snapshot -> snapshot.roundId())
                .containsExactly("round-1", "round-2");
    }

    @Test
    void totalLeaderboardUsesBestOfflineRoundAndLatestOnlineRecord() throws Exception {
        Path offline = temporaryDirectory.resolve("leaderboards.json");
        Files.writeString(offline, """
                [
                  {
                    "eventId": "demo",
                    "eventName": "聚合校验",
                    "roundId": "round-1",
                    "roundName": "第一轮",
                    "finishedAt": "2026-10-20T09:00:00Z",
                    "entries": [
                      {
                        "rank": 1,
                        "participantId": "p1",
                        "playerId": "a",
                        "nickname": "旧名",
                        "score": 20,
                        "durationMs": 8000,
                        "answeredCount": 2,
                        "totalQuestions": 3,
                        "joinedAt": "2026-10-20T08:55:00Z"
                      },
                      {
                        "rank": 2,
                        "participantId": "p1",
                        "playerId": "b",
                        "nickname": "甲",
                        "score": 30,
                        "durationMs": 10000,
                        "answeredCount": 3,
                        "totalQuestions": 3,
                        "joinedAt": "2026-10-20T08:56:00Z"
                      }
                    ]
                  },
                  {
                    "eventId": "demo",
                    "eventName": "聚合校验",
                    "roundId": "round-2",
                    "roundName": "第二轮",
                    "finishedAt": "2026-10-20T10:00:00Z",
                    "entries": [
                      {
                        "rank": 1,
                        "participantId": "p1",
                        "playerId": "c",
                        "nickname": "甲后",
                        "score": 10,
                        "durationMs": 6000,
                        "answeredCount": 1,
                        "totalQuestions": 3,
                        "joinedAt": "2026-10-20T09:55:00Z"
                      },
                      {
                        "rank": 2,
                        "participantId": "p2",
                        "playerId": "d",
                        "nickname": "乙",
                        "score": 50,
                        "durationMs": 5000,
                        "answeredCount": 3,
                        "totalQuestions": 3,
                        "joinedAt": "2026-10-20T09:56:00Z"
                      }
                    ]
                  }
                ]
                """, StandardCharsets.UTF_8);
        Path online = temporaryDirectory.resolve("online_leaderboard.json");
        Files.writeString(online, """
                [
                  {
                    "eventId": "demo",
                    "attemptId": "a1",
                    "participantId": "p1",
                    "nickname": "旧线上名",
                    "score": 100,
                    "answeredCount": 3,
                    "totalQuestions": 3,
                    "durationMs": 1000,
                    "questionSetVersion": "v1",
                    "answers": [],
                    "lockedAt": "2026-10-20T08:00:00Z"
                  },
                  {
                    "eventId": "demo",
                    "attemptId": "a2",
                    "participantId": "p1",
                    "nickname": "线上甲",
                    "score": 40,
                    "answeredCount": 3,
                    "totalQuestions": 3,
                    "durationMs": 20000,
                    "questionSetVersion": "v1",
                    "answers": [],
                    "lockedAt": "2026-10-20T11:00:00Z"
                  }
                ]
                """, StandardCharsets.UTF_8);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        LeaderboardStore store = new LeaderboardStore(
                objectMapper,
                offline.toString(),
                online.toString(),
                temporaryDirectory.resolve("leaderboard_summary.txt").toString());

        List<TotalLeaderboardEntry> entries = store.totalLeaderboard("demo");

        assertThat(entries).hasSize(2);
        TotalLeaderboardEntry first = entries.get(0);
        assertThat(first.participantId()).isEqualTo("p1");
        assertThat(first.totalScore()).isEqualTo(80);
        assertThat(first.offlineScore()).isEqualTo(40);
        assertThat(first.onlineScore()).isEqualTo(40);
        assertThat(first.totalDurationMs()).isEqualTo(36000L);
        assertThat(first.offlineRoundCount()).isEqualTo(2);
        assertThat(first.nickname()).isEqualTo("线上甲");
        assertThat(first.offlineContributions())
                .extracting(contribution -> contribution.roundId())
                .containsExactly("round-1", "round-2");
        assertThat(first.offlineContributions().get(0).score()).isEqualTo(30);
        assertThat(store.onlineLeaderboard("demo")).hasSize(1);
        assertThat(store.onlineLeaderboard("demo").get(0).score()).isEqualTo(40);
        assertThat(entries.get(1).participantId()).isEqualTo("p2");
    }
}
