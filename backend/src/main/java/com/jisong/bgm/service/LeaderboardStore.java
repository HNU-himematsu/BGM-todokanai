package com.jisong.bgm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisong.bgm.dto.LeaderboardEntry;
import com.jisong.bgm.dto.LeaderboardSnapshot;
import com.jisong.bgm.dto.OfflineContribution;
import com.jisong.bgm.dto.OnlineContribution;
import com.jisong.bgm.dto.OnlineLeaderboardEntry;
import com.jisong.bgm.dto.OnlineScoreRecord;
import com.jisong.bgm.dto.TotalLeaderboardEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LeaderboardStore {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final ObjectMapper objectMapper;
    private final Path filePath;
    private final Path onlineFilePath;
    private final Path txtPath;

    public LeaderboardStore(
            ObjectMapper objectMapper,
            @Value("${app.leaderboard-file:data/leaderboards.json}") String filePath,
            @Value("${app.online-leaderboard-file:data/online_leaderboard.json}") String onlineFilePath,
            @Value("${app.leaderboard-txt-file:data/leaderboard_summary.txt}") String txtFilePath
    ) {
        this.objectMapper = objectMapper;
        this.filePath = resolveFile(filePath);
        this.onlineFilePath = resolveFile(onlineFilePath);
        this.txtPath = resolveFile(txtFilePath);
    }

    private static Path resolveFile(String configuredPath) {
        Path direct = Path.of(configuredPath);
        if (direct.isAbsolute()) {
            return direct;
        }
        if (Files.exists(direct)) {
            return direct;
        }
        Path inBackend = Path.of("backend", configuredPath);
        if (Files.exists(inBackend)) {
            return inBackend;
        }
        return direct;
    }

    public synchronized void save(
            String eventId,
            String eventName,
            String roundId,
            String roundName,
            List<LeaderboardEntry> entries
    ) {
        List<LeaderboardSnapshot> history = loadOffline();
        history.add(new LeaderboardSnapshot(
                eventId, eventName, roundId, roundName, Instant.now(), entries));
        writeJsonAtomically(filePath, history);
        refreshSummary();
    }

    public synchronized OnlineScoreRecord saveOnline(OnlineScoreRecord record) {
        List<OnlineScoreRecord> records = loadOnline();
        OnlineScoreRecord sameAttempt = null;
        for (OnlineScoreRecord existing : records) {
            if (existing == null) {
                continue;
            }
            if (sameParticipant(existing, record)) {
                if (existing.attemptId() != null && existing.attemptId().equals(record.attemptId())) {
                    sameAttempt = preferredOnlineRecord(sameAttempt, existing);
                } else {
                    throw new ConflictException("该参与者已经锁定过线上成绩");
                }
            }
        }
        if (sameAttempt != null) {
            return sameAttempt;
        }
        records.add(record);
        writeJsonAtomically(onlineFilePath, records);
        refreshSummary();
        return record;
    }

    public synchronized OnlineScoreRecord onlineRecord(String eventId, String participantId) {
        OnlineScoreRecord found = null;
        for (OnlineScoreRecord record : loadOnline()) {
            if (record != null
                    && eventId.equals(record.eventId())
                    && participantId.equals(record.participantId())) {
                found = preferredOnlineRecord(found, record);
            }
        }
        return found;
    }

    public synchronized List<LeaderboardSnapshot> history(String eventId) {
        return loadOffline().stream()
                .filter(snapshot -> snapshot != null && eventId.equals(snapshot.eventId()))
                .sorted(Comparator.comparing(
                        LeaderboardSnapshot::finishedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public synchronized List<OnlineLeaderboardEntry> onlineLeaderboard(String eventId) {
        List<OnlineScoreRecord> records = canonicalOnlineRecords(eventId);
        records.sort(onlineComparator());
        List<OnlineLeaderboardEntry> entries = new ArrayList<>();
        for (int index = 0; index < records.size(); index++) {
            OnlineScoreRecord record = records.get(index);
            entries.add(new OnlineLeaderboardEntry(
                    index + 1,
                    record.participantId(),
                    record.nickname(),
                    record.score(),
                    record.answeredCount(),
                    record.totalQuestions(),
                    record.durationMs(),
                    record.lockedAt()));
        }
        return List.copyOf(entries);
    }

    public synchronized List<TotalLeaderboardEntry> totalLeaderboard(String eventId) {
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        List<LeaderboardSnapshot> snapshots = history(eventId);
        int sequence = 0;
        for (int snapshotIndex = 0; snapshotIndex < snapshots.size(); snapshotIndex++) {
            LeaderboardSnapshot snapshot = snapshots.get(snapshotIndex);
            int roundNumber = snapshotIndex + 1;
            String roundId = isBlank(snapshot.roundId())
                    ? "legacy-round-" + roundNumber
                    : snapshot.roundId();
            String roundName = isBlank(snapshot.roundName())
                    ? "第 " + roundNumber + " 轮"
                    : snapshot.roundName();
            Instant finishedAt = snapshot.finishedAt();
            for (LeaderboardEntry entry : snapshot.entries()) {
                if (entry == null) {
                    continue;
                }
                String participantId = mergeKey(entry);
                if (participantId == null) {
                    continue;
                }
                Aggregate aggregate = aggregates.computeIfAbsent(
                        participantId, key -> new Aggregate(key));
                OfflineBest candidate = new OfflineBest(
                        roundId, roundName, entry.nickname(), entry.score(), entry.durationMs(),
                        finishedAt, sequence++);
                aggregate.offer(candidate);
            }
        }
        for (OnlineScoreRecord record : canonicalOnlineRecords(eventId)) {
            if (isBlank(record.participantId())) {
                continue;
            }
            Aggregate aggregate = aggregates.computeIfAbsent(
                    record.participantId(), Aggregate::new);
            aggregate.online = record;
        }

        List<TotalLeaderboardEntry> entries = new ArrayList<>();
        for (Aggregate aggregate : aggregates.values()) {
            List<OfflineContribution> contributions = aggregate.rounds.values().stream()
                    .map(best -> new OfflineContribution(
                            best.roundId, best.roundName, best.score, best.durationMs, best.finishedAt))
                    .toList();
            int offlineScore = aggregate.rounds.values().stream().mapToInt(best -> best.score).sum();
            long offlineDuration = aggregate.rounds.values().stream().mapToLong(best -> best.durationMs).sum();
            int onlineScore = aggregate.online == null ? 0 : aggregate.online.score();
            long onlineDuration = aggregate.online == null ? 0L : aggregate.online.durationMs();
            Instant offlineLast = aggregate.rounds.values().stream()
                    .map(best -> best.finishedAt)
                    .filter(value -> value != null)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            Instant onlineLast = aggregate.online == null ? null : aggregate.online.lockedAt();
            Instant lastActivityAt = later(offlineLast, onlineLast);
            String nickname = aggregate.displayNickname(offlineLast, onlineLast);
            entries.add(new TotalLeaderboardEntry(
                    0,
                    aggregate.participantId,
                    nickname,
                    offlineScore + onlineScore,
                    offlineScore,
                    onlineScore,
                    offlineDuration + onlineDuration,
                    aggregate.rounds.size(),
                    aggregate.online != null,
                    lastActivityAt,
                    contributions,
                    aggregate.online == null ? null : new OnlineContribution(
                            aggregate.online.score(), aggregate.online.durationMs(), aggregate.online.lockedAt())));
        }
        entries.sort(Comparator.comparingInt(TotalLeaderboardEntry::totalScore).reversed()
                .thenComparingLong(TotalLeaderboardEntry::totalDurationMs)
                .thenComparing(TotalLeaderboardEntry::lastActivityAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TotalLeaderboardEntry::nickname,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        List<TotalLeaderboardEntry> ranked = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            TotalLeaderboardEntry entry = entries.get(index);
            ranked.add(new TotalLeaderboardEntry(
                    index + 1,
                    entry.participantId(),
                    entry.nickname(),
                    entry.totalScore(),
                    entry.offlineScore(),
                    entry.onlineScore(),
                    entry.totalDurationMs(),
                    entry.offlineRoundCount(),
                    entry.onlineLocked(),
                    entry.lastActivityAt(),
                    entry.offlineContributions(),
                    entry.onlineContribution()));
        }
        return List.copyOf(ranked);
    }

    public synchronized int onlineRank(String eventId, String participantId) {
        return rankInOnline(onlineLeaderboard(eventId), participantId);
    }

    public synchronized int totalRank(String eventId, String participantId) {
        for (TotalLeaderboardEntry entry : totalLeaderboard(eventId)) {
            if (entry.participantId().equals(participantId)) {
                return entry.rank();
            }
        }
        return 0;
    }

    public synchronized void exportSummaryTxt() {
        refreshSummary();
    }

    public synchronized String getSummaryTxt() {
        return generateSummaryTxt(loadOffline(), loadOnline());
    }

    private void refreshSummary() {
        String content = generateSummaryTxt(loadOffline(), loadOnline());
        writeTextAtomically(txtPath, content);
    }

    private String generateSummaryTxt(
            List<LeaderboardSnapshot> history,
            List<OnlineScoreRecord> onlineRecords
    ) {
        StringBuilder sb = new StringBuilder();
        history = new ArrayList<>(history);
        history.sort(Comparator.comparing(
                LeaderboardSnapshot::finishedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Map<String, String> eventNames = new LinkedHashMap<>();
        for (LeaderboardSnapshot snapshot : history) {
            if (!isBlank(snapshot.eventId()) && !isBlank(snapshot.eventName())) {
                eventNames.putIfAbsent(snapshot.eventId(), snapshot.eventName());
            }
        }
        int roundIndex = 1;
        for (LeaderboardSnapshot snapshot : history) {
            String finishTime = snapshot.finishedAt() == null
                    ? "未知时间"
                    : TIME_FORMATTER.format(snapshot.finishedAt());
            String roundName = isBlank(snapshot.roundName())
                    ? "第 " + roundIndex + " 轮"
                    : snapshot.roundName();
            List<LeaderboardEntry> entries = snapshot.entries();
            sb.append("=".repeat(80)).append(System.lineSeparator());
            sb.append(String.format("【线下轮次榜 · %s】%s%n",
                    roundName, eventDisplayName(snapshot.eventId(), eventNames)));
            sb.append(String.format("结束时间: %s | 参与人数: %d 人%n", finishTime, entries.size()));
            sb.append("-".repeat(80)).append(System.lineSeparator());
            sb.append(String.format("%s %s %s %s %s %s%n",
                    padRight("排名", 10),
                    padRight("玩家昵称", 18),
                    padRight("得分", 10),
                    padRight("耗时", 14),
                    padRight("已答", 10),
                    "参与者ID"));
            sb.append("-".repeat(80)).append(System.lineSeparator());
            if (entries.isEmpty()) {
                sb.append("（本轮暂无有效答题记录）").append(System.lineSeparator());
            } else {
                for (LeaderboardEntry entry : entries) {
                    sb.append(String.format("%s %s %s %s %s %s%n",
                            padRight("第 " + entry.rank() + " 名", 10),
                            padRight(entry.nickname(), 18),
                            padRight(entry.score() + " 分", 10),
                            padRight(String.format("%.1f 秒", entry.durationMs() / 1000.0), 14),
                            padRight(entry.answeredCount() + "/" + entry.totalQuestions(), 10),
                            participantId(entry)));
                }
            }
            roundIndex++;
        }

        Map<String, List<OnlineScoreRecord>> onlineByEvent = groupOnlineByEvent(onlineRecords);
        if (onlineByEvent.isEmpty()) {
            sb.append("=".repeat(80)).append(System.lineSeparator());
            sb.append("【线上积分榜】").append(System.lineSeparator());
            sb.append("（暂无线上锁定记录）").append(System.lineSeparator());
        } else {
            for (Map.Entry<String, List<OnlineScoreRecord>> pair : onlineByEvent.entrySet()) {
                List<OnlineScoreRecord> records = canonicalOnlineRecords(pair.getValue());
                records.sort(onlineComparator());
                sb.append("=".repeat(80)).append(System.lineSeparator());
                sb.append(String.format("【线上积分榜】%s%n",
                        eventDisplayName(pair.getKey(), eventNames)));
                sb.append(String.format("生成时间: %s | 锁定人数: %d 人%n",
                        TIME_FORMATTER.format(Instant.now()), records.size()));
                sb.append("-".repeat(80)).append(System.lineSeparator());
                sb.append(String.format("%s %s %s %s %s %s%n",
                        padRight("排名", 10),
                        padRight("玩家昵称", 18),
                        padRight("得分", 10),
                        padRight("耗时", 14),
                        padRight("已答", 10),
                        "锁定时间"));
                sb.append("-".repeat(80)).append(System.lineSeparator());
                int rank = 1;
                for (OnlineScoreRecord record : records) {
                    sb.append(String.format("%s %s %s %s %s %s%n",
                            padRight("第 " + rank++ + " 名", 10),
                            padRight(record.nickname(), 18),
                            padRight(record.score() + " 分", 10),
                            padRight(String.format("%.1f 秒", record.durationMs() / 1000.0), 14),
                            padRight(record.answeredCount() + "/" + record.totalQuestions(), 10),
                            record.lockedAt() == null ? "未知时间" : TIME_FORMATTER.format(record.lockedAt())));
                }
            }
        }

        Set<String> eventIds = new LinkedHashSet<>();
        history.forEach(snapshot -> eventIds.add(snapshot.eventId()));
        onlineRecords.forEach(record -> eventIds.add(record.eventId()));
        if (eventIds.isEmpty()) {
            sb.append("=".repeat(80)).append(System.lineSeparator());
            sb.append("【总榜】").append(System.lineSeparator());
            sb.append("规则: 线下各轮最佳成绩之和 + 线上锁定成绩").append(System.lineSeparator());
            sb.append("（暂无有效成绩）").append(System.lineSeparator());
        } else {
            for (String eventId : eventIds) {
                List<TotalLeaderboardEntry> entries = totalLeaderboard(eventId);
                sb.append("=".repeat(80)).append(System.lineSeparator());
                sb.append(String.format("【总榜】%s%n", eventDisplayName(eventId, eventNames)));
                sb.append("规则: 线下各轮最佳成绩之和 + 线上锁定成绩").append(System.lineSeparator());
                sb.append("-".repeat(80)).append(System.lineSeparator());
                sb.append(String.format("%s %s %s %s %s %s %s %s%n",
                        padRight("排名", 10),
                        padRight("玩家昵称", 18),
                        padRight("总分", 10),
                        padRight("线下分", 10),
                        padRight("线上分", 10),
                        padRight("总耗时", 14),
                        padRight("线下轮次", 12),
                        "线上锁定"));
                sb.append("-".repeat(80)).append(System.lineSeparator());
                if (entries.isEmpty()) {
                    sb.append("（暂无有效成绩）").append(System.lineSeparator());
                } else {
                    for (TotalLeaderboardEntry entry : entries) {
                        sb.append(String.format("%s %s %s %s %s %s %s %s%n",
                                padRight("第 " + entry.rank() + " 名", 10),
                                padRight(entry.nickname(), 18),
                                padRight(entry.totalScore() + " 分", 10),
                                padRight(entry.offlineScore() + " 分", 10),
                                padRight(entry.onlineScore() + " 分", 10),
                                padRight(String.format("%.1f 秒", entry.totalDurationMs() / 1000.0), 14),
                                padRight(entry.offlineRoundCount() + " 轮", 12),
                                entry.onlineLocked() ? "是" : "否"));
                    }
                }
            }
        }
        sb.append("=".repeat(80)).append(System.lineSeparator());
        return sb.toString();
    }

    private Map<String, List<OnlineScoreRecord>> groupOnlineByEvent(List<OnlineScoreRecord> records) {
        Map<String, List<OnlineScoreRecord>> grouped = new LinkedHashMap<>();
        for (OnlineScoreRecord record : records) {
            if (record == null || isBlank(record.eventId())) {
                continue;
            }
            grouped.computeIfAbsent(record.eventId(), key -> new ArrayList<>()).add(record);
        }
        return grouped;
    }

    private static String eventDisplayName(String eventId, Map<String, String> eventNames) {
        String name = eventNames.get(eventId);
        return isBlank(name) ? eventId : name;
    }

    private List<OnlineScoreRecord> canonicalOnlineRecords(String eventId) {
        List<OnlineScoreRecord> records = loadOnline().stream()
                .filter(record -> record != null && eventId.equals(record.eventId()))
                .toList();
        return canonicalOnlineRecords(records);
    }

    private List<OnlineScoreRecord> canonicalOnlineRecords(List<OnlineScoreRecord> records) {
        Map<String, OnlineScoreRecord> byParticipant = new LinkedHashMap<>();
        for (OnlineScoreRecord record : records) {
            if (record == null || isBlank(record.participantId())) {
                continue;
            }
            OnlineScoreRecord existing = byParticipant.get(record.participantId());
            byParticipant.put(record.participantId(), preferredOnlineRecord(existing, record));
        }
        return new ArrayList<>(byParticipant.values());
    }

    private static OnlineScoreRecord preferredOnlineRecord(
            OnlineScoreRecord current,
            OnlineScoreRecord candidate
    ) {
        if (current == null) {
            return candidate;
        }
        Instant currentTime = current.lockedAt() == null ? Instant.MIN : current.lockedAt();
        Instant candidateTime = candidate.lockedAt() == null ? Instant.MIN : candidate.lockedAt();
        int comparison = candidateTime.compareTo(currentTime);
        if (comparison > 0) {
            return candidate;
        }
        if (comparison < 0) {
            return current;
        }
        String currentAttempt = current.attemptId() == null ? "" : current.attemptId();
        String candidateAttempt = candidate.attemptId() == null ? "" : candidate.attemptId();
        return candidateAttempt.compareTo(currentAttempt) < 0 ? candidate : current;
    }

    private static Comparator<OnlineScoreRecord> onlineComparator() {
        return Comparator.comparingInt(OnlineScoreRecord::score).reversed()
                .thenComparingLong(OnlineScoreRecord::durationMs)
                .thenComparing(OnlineScoreRecord::lockedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(OnlineScoreRecord::nickname,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static int rankInOnline(List<OnlineLeaderboardEntry> entries, String participantId) {
        for (OnlineLeaderboardEntry entry : entries) {
            if (entry.participantId().equals(participantId)) {
                return entry.rank();
            }
        }
        return 0;
    }

    private static String mergeKey(LeaderboardEntry entry) {
        if (!isBlank(entry.participantId())) {
            return entry.participantId();
        }
        return isBlank(entry.playerId()) ? null : entry.playerId();
    }

    private static String participantId(LeaderboardEntry entry) {
        String key = mergeKey(entry);
        return key == null ? "" : key;
    }

    private static boolean sameParticipant(OnlineScoreRecord left, OnlineScoreRecord right) {
        return left.eventId() != null
                && left.eventId().equals(right.eventId())
                && left.participantId() != null
                && left.participantId().equals(right.participantId());
    }

    private static Instant later(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    private static int displayWidth(String value) {
        if (value == null) {
            return 0;
        }
        int width = 0;
        for (char character : value.toCharArray()) {
            width += (character > 127) ? 2 : 1;
        }
        return width;
    }

    private static String padRight(String value, int targetWidth) {
        if (value == null) {
            value = "";
        }
        int currentWidth = displayWidth(value);
        if (currentWidth >= targetWidth) {
            return value;
        }
        return value + " ".repeat(targetWidth - currentWidth);
    }

    private List<LeaderboardSnapshot> loadOffline() {
        if (!Files.exists(filePath)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(objectMapper.readValue(
                    filePath.toFile(), new TypeReference<List<LeaderboardSnapshot>>() {}));
        } catch (IOException exception) {
            throw new StorageException("线下排行榜文件读取失败：" + filePath.toAbsolutePath(), exception);
        }
    }

    private List<OnlineScoreRecord> loadOnline() {
        if (!Files.exists(onlineFilePath)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(objectMapper.readValue(
                    onlineFilePath.toFile(), new TypeReference<List<OnlineScoreRecord>>() {}));
        } catch (IOException exception) {
            throw new StorageException("线上排行榜文件读取失败：" + onlineFilePath.toAbsolutePath(), exception);
        }
    }

    private void writeJsonAtomically(Path target, Object data) {
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = Files.createTempFile(
                    parent, target.getFileName().toString(), ".tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), data);
                try {
                    Files.move(temporary, target,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new StorageException("排行榜保存失败：" + target.toAbsolutePath(), exception);
        }
    }

    private void writeTextAtomically(Path target, String content) {
        try {
            Path absoluteTarget = target.toAbsolutePath();
            Path parent = absoluteTarget.getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(
                    parent, absoluteTarget.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, absoluteTarget,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new StorageException("排行榜文本文件导出失败：" + target.toAbsolutePath(), exception);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class Aggregate {
        private final String participantId;
        private final Map<String, OfflineBest> rounds = new LinkedHashMap<>();
        private OnlineScoreRecord online;

        private Aggregate(String participantId) {
            this.participantId = participantId;
        }

        private void offer(OfflineBest candidate) {
            OfflineBest existing = rounds.get(candidate.roundId);
            if (existing == null || candidate.betterThan(existing)) {
                rounds.put(candidate.roundId, candidate);
            }
        }

        private String displayNickname(Instant offlineLast, Instant onlineLast) {
            if (online != null && onlineLast != null
                    && (offlineLast == null || !onlineLast.isBefore(offlineLast))) {
                return online.nickname();
            }
            OfflineBest latest = null;
            for (OfflineBest best : rounds.values()) {
                if (latest == null) {
                    latest = best;
                } else if (best.finishedAt != null
                        && (latest.finishedAt == null || best.finishedAt.isAfter(latest.finishedAt))) {
                    latest = best;
                }
            }
            return latest == null ? "" : latest.nickname;
        }
    }

    private static final class OfflineBest {
        private final String roundId;
        private final String roundName;
        private final String nickname;
        private final int score;
        private final long durationMs;
        private final Instant finishedAt;
        private final int sequence;

        private OfflineBest(
                String roundId,
                String roundName,
                String nickname,
                int score,
                long durationMs,
                Instant finishedAt,
                int sequence
        ) {
            this.roundId = roundId;
            this.roundName = roundName;
            this.nickname = nickname == null ? "" : nickname;
            this.score = score;
            this.durationMs = Math.max(0L, durationMs);
            this.finishedAt = finishedAt;
            this.sequence = sequence;
        }

        private boolean betterThan(OfflineBest other) {
            if (score != other.score) {
                return score > other.score;
            }
            if (durationMs != other.durationMs) {
                return durationMs < other.durationMs;
            }
            if (finishedAt != null && other.finishedAt != null && !finishedAt.equals(other.finishedAt)) {
                return finishedAt.isBefore(other.finishedAt);
            }
            if (finishedAt != null && other.finishedAt == null) {
                return true;
            }
            if (finishedAt == null && other.finishedAt != null) {
                return false;
            }
            return sequence < other.sequence;
        }
    }
}
