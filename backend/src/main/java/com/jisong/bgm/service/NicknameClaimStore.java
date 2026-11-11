package com.jisong.bgm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisong.bgm.dto.NicknameClaim;
import com.jisong.bgm.dto.NicknameConflict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class NicknameClaimStore {
    private static final Logger log = LoggerFactory.getLogger(NicknameClaimStore.class);
    private static final int MAX_CONFLICTS = 200;
    private static final String SOURCE_JOIN = "JOIN";
    private static final String SOURCE_HOST = "HOST";

    private final ObjectMapper objectMapper;
    private final Path filePath;
    private final List<NicknameConflict> conflicts = new ArrayList<>();

    public NicknameClaimStore(
            ObjectMapper objectMapper,
            @Value("${app.nickname-claims-file:data/nickname_claims.json}") String filePath
    ) {
        this.objectMapper = objectMapper;
        this.filePath = resolveFile(filePath);
    }

    public synchronized NicknameClaim claim(String eventId, String nickname, String participantId) {
        return claim(eventId, nickname, participantId, false);
    }

    public synchronized NicknameClaim resolveForLock(String eventId, String nickname, String participantId) {
        return claim(eventId, nickname, participantId, true);
    }

    public synchronized List<NicknameClaim> list(String eventId) {
        List<NicknameClaim> result = new ArrayList<>();
        for (NicknameClaim claim : load()) {
            if (eventId.equals(claim.eventId())) {
                result.add(claim);
            }
        }
        result.sort(Comparator.comparing(
                NicknameClaim::claimedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return List.copyOf(result);
    }

    public synchronized void release(String eventId, String claimId) {
        List<NicknameClaim> claims = load();
        NicknameClaim target = findByClaimId(claims, eventId, claimId);
        if (target == null) {
            throw new ResourceNotFoundException("昵称认领记录不存在");
        }
        claims.remove(target);
        writeJsonAtomically(filePath, claims);
        conflicts.removeIf(conflict -> eventId.equals(conflict.eventId())
                && target.nicknameKey().equals(conflict.nicknameKey()));
    }

    public synchronized NicknameClaim rebind(String eventId, String claimId, String participantId) {
        String normalizedId = normalizeParticipantId(participantId);
        List<NicknameClaim> claims = load();
        NicknameClaim target = findByClaimId(claims, eventId, claimId);
        if (target == null) {
            throw new ResourceNotFoundException("昵称认领记录不存在");
        }
        NicknameClaim occupied = findByIdentity(claims, eventId, normalizedId);
        if (occupied != null && !occupied.claimId().equals(claimId)) {
            throw new ConflictException("该身份已认领昵称「" + occupied.nickname() + "」，请先释放");
        }
        NicknameClaim updated = new NicknameClaim(
                target.claimId(),
                target.eventId(),
                target.nicknameKey(),
                target.nickname(),
                normalizedId,
                SOURCE_HOST,
                target.claimedAt(),
                Instant.now());
        claims.set(claims.indexOf(target), updated);
        writeJsonAtomically(filePath, claims);
        conflicts.removeIf(conflict -> eventId.equals(conflict.eventId())
                && target.nicknameKey().equals(conflict.nicknameKey()));
        return updated;
    }

    public synchronized List<NicknameConflict> conflicts(String eventId) {
        List<NicknameConflict> result = new ArrayList<>();
        for (NicknameConflict conflict : conflicts) {
            if (eventId.equals(conflict.eventId())) {
                result.add(conflict);
            }
        }
        return List.copyOf(result);
    }

    private NicknameClaim claim(
            String eventId,
            String nickname,
            String participantId,
            boolean preferExistingIdentity
    ) {
        String nicknameKey = NicknameKeys.of(nickname);
        if (nicknameKey.isBlank()) {
            throw new IllegalArgumentException("昵称不能为空");
        }
        String normalizedId = normalizeParticipantId(participantId);
        List<NicknameClaim> claims = load();
        NicknameClaim byKey = findByKey(claims, eventId, nicknameKey);
        NicknameClaim byIdentity = findByIdentity(claims, eventId, normalizedId);
        if (preferExistingIdentity && byIdentity != null) {
            return byIdentity;
        }
        if (byKey != null) {
            if (normalizedId.equals(byKey.participantId())) {
                return byKey;
            }
            recordConflict(eventId, nickname, nicknameKey, normalizedId);
            throw new NicknameTakenException("该昵称已被注册，请更换昵称或联系主持人找回身份");
        }
        if (byIdentity != null) {
            throw new NicknameTakenException(
                    "该身份已使用昵称「" + byIdentity.nickname() + "」，请使用原昵称或联系主持人");
        }
        Instant now = Instant.now();
        NicknameClaim created = new NicknameClaim(
                "c-" + UUID.randomUUID(),
                eventId,
                nicknameKey,
                nickname,
                normalizedId,
                SOURCE_JOIN,
                now,
                now);
        claims.add(created);
        writeJsonAtomically(filePath, claims);
        return created;
    }

    private void recordConflict(String eventId, String nickname, String nicknameKey, String participantId) {
        conflicts.removeIf(conflict -> eventId.equals(conflict.eventId())
                && nicknameKey.equals(conflict.nicknameKey())
                && participantId.equals(conflict.participantId()));
        conflicts.add(0, new NicknameConflict(
                eventId, nickname, nicknameKey, participantId, Instant.now()));
        while (conflicts.size() > MAX_CONFLICTS) {
            conflicts.remove(conflicts.size() - 1);
        }
    }

    private List<NicknameClaim> load() {
        if (!Files.exists(filePath)) {
            return new ArrayList<>();
        }
        try {
            List<NicknameClaim> loaded = objectMapper.readValue(
                    filePath.toFile(), new TypeReference<List<NicknameClaim>>() {});
            List<NicknameClaim> result = new ArrayList<>();
            for (NicknameClaim claim : loaded) {
                if (claim != null) {
                    result.add(claim);
                }
            }
            return result;
        } catch (IOException exception) {
            throw new StorageException("昵称认领文件读取失败：" + filePath.toAbsolutePath(), exception);
        }
    }

    private static NicknameClaim findByKey(List<NicknameClaim> claims, String eventId, String nicknameKey) {
        for (NicknameClaim claim : claims) {
            if (eventId.equals(claim.eventId()) && nicknameKey.equals(claim.nicknameKey())) {
                return claim;
            }
        }
        return null;
    }

    private static NicknameClaim findByIdentity(
            List<NicknameClaim> claims,
            String eventId,
            String participantId
    ) {
        for (NicknameClaim claim : claims) {
            if (eventId.equals(claim.eventId()) && participantId.equals(claim.participantId())) {
                return claim;
            }
        }
        return null;
    }

    private static NicknameClaim findByClaimId(
            List<NicknameClaim> claims,
            String eventId,
            String claimId
    ) {
        for (NicknameClaim claim : claims) {
            if (eventId.equals(claim.eventId()) && claimId.equals(claim.claimId())) {
                return claim;
            }
        }
        return null;
    }

    private static String normalizeParticipantId(String participantId) {
        String value = participantId == null ? "" : participantId.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("参与者标识不能为空");
        }
        if (value.length() > 64) {
            throw new IllegalArgumentException("参与者标识不能超过64个字符");
        }
        return value;
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

    private void writeJsonAtomically(Path target, Object data) {
        try {
            Path absoluteTarget = target.toAbsolutePath();
            Path parent = absoluteTarget.getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(
                    parent, absoluteTarget.getFileName().toString(), ".tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), data);
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
            log.error("昵称认领文件写入失败：{}", target.toAbsolutePath(), exception);
            throw new StorageException("昵称认领保存失败：" + target.toAbsolutePath(), exception);
        }
    }
}
