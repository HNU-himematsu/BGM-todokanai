package com.jisong.bgm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisong.bgm.dto.LeaderboardEntry;
import com.jisong.bgm.dto.LeaderboardSnapshot;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LeaderboardStore {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final ObjectMapper objectMapper;
    private final Path filePath;
    private final Path txtPath;

    public LeaderboardStore(
            ObjectMapper objectMapper,
            @Value("${app.leaderboard-file:data/leaderboards.json}") String filePath,
            @Value("${app.leaderboard-txt-file:data/leaderboard_summary.txt}") String txtFilePath
    ) {
        this.objectMapper = objectMapper;
        this.filePath = resolveFile(filePath);
        this.txtPath = resolveFile(txtFilePath);
    }

    private static Path resolveFile(String configuredPath) {
        Path direct = Path.of(configuredPath);
        if (Files.exists(direct)) {
            return direct;
        }
        Path inBackend = Path.of("backend", configuredPath);
        if (Files.exists(inBackend)) {
            return inBackend;
        }
        return direct;
    }

    @PostConstruct
    void init() {
        if (Files.exists(filePath)) {
            try {
                writeTxtSummary(load());
            } catch (Exception ignored) {
                // Keep startup resilient
            }
        }
    }

    public synchronized void save(String eventId, String eventName, List<LeaderboardEntry> entries) {
        List<LeaderboardSnapshot> history = load();
        history.add(new LeaderboardSnapshot(eventId, eventName, Instant.now(), entries));
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), history);
        } catch (IOException exception) {
            throw new IllegalStateException("排行榜保存失败：" + filePath.toAbsolutePath(), exception);
        }
        writeTxtSummary(history);
    }

    public synchronized List<LeaderboardSnapshot> history(String eventId) {
        return load().stream()
                .filter(snapshot -> snapshot.eventId().equals(eventId))
                .toList();
    }

    public synchronized void exportSummaryTxt() {
        writeTxtSummary(load());
    }

    public synchronized String getSummaryTxt() {
        if (Files.exists(txtPath)) {
            try {
                return Files.readString(txtPath, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }
        return generateSummaryTxt(load());
    }

    private void writeTxtSummary(List<LeaderboardSnapshot> history) {
        String content = generateSummaryTxt(history);
        try {
            Path parent = txtPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(txtPath, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("排行榜文本文件导出失败：" + txtPath.toAbsolutePath(), exception);
        }
    }

    private String generateSummaryTxt(List<LeaderboardSnapshot> history) {
        StringBuilder sb = new StringBuilder();
        int roundIndex = 1;
        for (LeaderboardSnapshot snapshot : history) {
            String finishTime = snapshot.finishedAt() != null
                    ? TIME_FORMATTER.format(snapshot.finishedAt())
                    : "未知时间";
            int playerCount = snapshot.entries() != null ? snapshot.entries().size() : 0;

            sb.append("=".repeat(80)).append(System.lineSeparator());
            sb.append(String.format("【第 %d 轮】%s%n", roundIndex++, snapshot.eventName()));
            sb.append(String.format("结束时间: %s | 参与人数: %d 人%n", finishTime, playerCount));
            sb.append("-".repeat(80)).append(System.lineSeparator());
            sb.append(String.format("%s %s %s %s %s%n",
                    padRight("排名", 10),
                    padRight("玩家昵称", 18),
                    padRight("最终得分", 10),
                    padRight("答题耗时", 14),
                    "玩家ID"
            ));
            sb.append("-".repeat(80)).append(System.lineSeparator());

            if (playerCount == 0) {
                sb.append("（本轮无有效答题记录）").append(System.lineSeparator());
            } else {
                for (LeaderboardEntry entry : snapshot.entries()) {
                    double durationSec = entry.durationMs() != null ? entry.durationMs() / 1000.0 : 0.0;
                    String rankStr = String.format("第 %d 名", entry.rank());
                    String scoreStr = entry.score() + " 分";
                    String durationStr = String.format("%.1f 秒", durationSec);

                    sb.append(String.format("%s %s %s %s %s%n",
                            padRight(rankStr, 10),
                            padRight(entry.nickname(), 18),
                            padRight(scoreStr, 10),
                            padRight(durationStr, 14),
                            entry.playerId()
                    ));
                }
            }
            sb.append(System.lineSeparator());
        }

        appendGrandSummary(sb, history);

        return sb.toString();
    }

    private void appendGrandSummary(StringBuilder sb, List<LeaderboardSnapshot> history) {
        List<OverallEntry> allEntries = new ArrayList<>();
        int roundIndex = 1;
        for (LeaderboardSnapshot snapshot : history) {
            String finishTime = snapshot.finishedAt() != null
                    ? TIME_FORMATTER.format(snapshot.finishedAt())
                    : "未知时间";
            if (snapshot.entries() != null) {
                for (LeaderboardEntry entry : snapshot.entries()) {
                    allEntries.add(new OverallEntry(
                            roundIndex,
                            entry.rank(),
                            entry.nickname(),
                            entry.score(),
                            entry.durationMs() != null ? entry.durationMs() : 0L,
                            snapshot.finishedAt(),
                            finishTime,
                            entry.playerId()
                    ));
                }
            }
            roundIndex++;
        }

        allEntries.sort(Comparator.comparingInt(OverallEntry::score).reversed()
                .thenComparingLong(OverallEntry::durationMs)
                .thenComparing(e -> e.finishedAt() != null ? e.finishedAt() : Instant.EPOCH)
                .thenComparingInt(OverallEntry::roundRank)
                .thenComparing(OverallEntry::nickname));

        long uniquePlayers = allEntries.stream().map(OverallEntry::nickname).distinct().count();
        int totalRounds = history.size();
        int totalPlays = allEntries.size();

        sb.append("=".repeat(120)).append(System.lineSeparator());
        sb.append("【全场总排行榜 · 所有轮次大总结】").append(System.lineSeparator());
        sb.append(String.format("总轮次: %d 轮 | 累计答题人次: %d 人次 | 独立玩家数: %d 人%n", totalRounds, totalPlays, uniquePlayers));
        sb.append("排名规则: 最终得分降序 > 答题耗时升序 > 轮次时间升序 > 轮内排名").append(System.lineSeparator());
        sb.append("-".repeat(120)).append(System.lineSeparator());
        sb.append(String.format("%s %s %s %s %s %s %s%n",
                padRight("总排名", 10),
                padRight("玩家昵称", 18),
                padRight("最终得分", 10),
                padRight("答题耗时", 14),
                padRight("所属轮次", 12),
                padRight("结束时间", 22),
                "玩家ID"
        ));
        sb.append("-".repeat(120)).append(System.lineSeparator());

        if (allEntries.isEmpty()) {
            sb.append("（全场暂无有效答题记录）").append(System.lineSeparator());
        } else {
            int rank = 1;
            for (OverallEntry entry : allEntries) {
                double durationSec = entry.durationMs() / 1000.0;
                String rankStr = String.format("第 %d 名", rank++);
                String scoreStr = entry.score() + " 分";
                String durationStr = String.format("%.1f 秒", durationSec);
                String roundStr = String.format("第 %d 轮", entry.roundIndex());

                sb.append(String.format("%s %s %s %s %s %s %s%n",
                        padRight(rankStr, 10),
                        padRight(entry.nickname(), 18),
                        padRight(scoreStr, 10),
                        padRight(durationStr, 14),
                        padRight(roundStr, 12),
                        padRight(entry.finishedAtStr(), 22),
                        entry.playerId()
                ));
            }
        }

        if (uniquePlayers < totalPlays) {
            Map<String, List<OverallEntry>> byNickname = new LinkedHashMap<>();
            for (OverallEntry entry : allEntries) {
                byNickname.computeIfAbsent(entry.nickname(), k -> new ArrayList<>()).add(entry);
            }

            sb.append("-".repeat(120)).append(System.lineSeparator());
            sb.append(String.format("【个人最佳成绩总榜 · 奖品发放参考（按昵称去重，共 %d 人）】%n", uniquePlayers));
            sb.append("规则说明: 依据“每人限制参与一次，奖品先到先得”，多次参与者仅取其单轮最佳成绩参与最终排名").append(System.lineSeparator());
            sb.append("-".repeat(120)).append(System.lineSeparator());
            sb.append(String.format("%s %s %s %s %s %s %s%n",
                    padRight("总排名", 10),
                    padRight("玩家昵称", 18),
                    padRight("最高得分", 10),
                    padRight("对应耗时", 14),
                    padRight("产生轮次", 12),
                    padRight("参与次数", 16),
                    "玩家ID"
            ));
            sb.append("-".repeat(120)).append(System.lineSeparator());

            int bestRank = 1;
            for (Map.Entry<String, List<OverallEntry>> pair : byNickname.entrySet()) {
                OverallEntry best = pair.getValue().get(0);
                int count = pair.getValue().size();
                double durationSec = best.durationMs() / 1000.0;
                String rankStr = String.format("第 %d 名", bestRank++);
                String scoreStr = best.score() + " 分";
                String durationStr = String.format("%.1f 秒", durationSec);
                String roundStr = String.format("第 %d 轮", best.roundIndex());
                String countStr = String.format("共参与 %d 轮", count);

                sb.append(String.format("%s %s %s %s %s %s %s%n",
                        padRight(rankStr, 10),
                        padRight(best.nickname(), 18),
                        padRight(scoreStr, 10),
                        padRight(durationStr, 14),
                        padRight(roundStr, 12),
                        padRight(countStr, 16),
                        best.playerId()
                ));
            }
        }

        sb.append("=".repeat(120)).append(System.lineSeparator());
    }

    private static int displayWidth(String str) {
        if (str == null) return 0;
        int width = 0;
        for (char c : str.toCharArray()) {
            width += (c > 127) ? 2 : 1;
        }
        return width;
    }

    private static String padRight(String str, int targetWidth) {
        if (str == null) str = "";
        int currentWidth = displayWidth(str);
        if (currentWidth >= targetWidth) {
            return str;
        }
        return str + " ".repeat(targetWidth - currentWidth);
    }

    private List<LeaderboardSnapshot> load() {
        if (!Files.exists(filePath)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(objectMapper.readValue(
                    filePath.toFile(),
                    new TypeReference<List<LeaderboardSnapshot>>() {
                    }
            ));
        } catch (IOException exception) {
            throw new IllegalStateException("排行榜文件读取失败：" + filePath.toAbsolutePath(), exception);
        }
    }

    private record OverallEntry(
            int roundIndex,
            int roundRank,
            String nickname,
            int score,
            long durationMs,
            Instant finishedAt,
            String finishedAtStr,
            String playerId
    ) {}
}
