package com.jisong.bgm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisong.bgm.dto.NicknameClaim;
import com.jisong.bgm.service.NicknameClaimStore;
import com.jisong.bgm.service.NicknameTakenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NicknameClaimStoreTests {
    @TempDir
    Path temporaryDirectory;

    private NicknameClaimStore store(String fileName) {
        return new NicknameClaimStore(
                new ObjectMapper().findAndRegisterModules(),
                temporaryDirectory.resolve(fileName).toString());
    }

    @Test
    void firstClaimRecordsOwnershipAndReusesItAcrossRounds() {
        NicknameClaimStore store = store("claims.json");

        NicknameClaim first = store.claim("demo", "aki", "p1");
        NicknameClaim second = store.claim("demo", "aki", "p1");

        assertThat(second.claimId()).isEqualTo(first.claimId());
        assertThat(store.list("demo")).hasSize(1);
        assertThat(store.list("demo").get(0).participantId()).isEqualTo("p1");
    }

    @Test
    void otherIdentityIsRejectedAndRecordedAsConflict() {
        NicknameClaimStore store = store("claims.json");
        store.claim("demo", "aki", "p1");

        assertThatThrownBy(() -> store.claim("demo", "aki", "p2"))
                .isInstanceOf(NicknameTakenException.class)
                .hasMessageContaining("已被注册");

        assertThat(store.conflicts("demo")).hasSize(1);
        assertThat(store.conflicts("demo").get(0).participantId()).isEqualTo("p2");
    }

    @Test
    void repeatedConflictAttemptsAreDeduplicated() {
        NicknameClaimStore store = store("claims.json");
        store.claim("demo", "aki", "p1");

        assertThatThrownBy(() -> store.claim("demo", "aki", "p2"))
                .isInstanceOf(NicknameTakenException.class);
        assertThatThrownBy(() -> store.claim("demo", "aki", "p2"))
                .isInstanceOf(NicknameTakenException.class);

        assertThat(store.conflicts("demo")).hasSize(1);
    }

    @Test
    void normalizationIgnoresWidthCaseAndSurroundingWhitespace() {
        NicknameClaimStore store = store("claims.json");
        store.claim("demo", "ＡＫＩ", "p1");

        assertThatThrownBy(() -> store.claim("demo", "  aki  ", "p2"))
                .isInstanceOf(NicknameTakenException.class);
        assertThat(store.claim("demo", "aki", "p1").participantId()).isEqualTo("p1");
    }

    @Test
    void identityCannotClaimASecondNickname() {
        NicknameClaimStore store = store("claims.json");
        store.claim("demo", "aki", "p1");

        assertThatThrownBy(() -> store.claim("demo", "other", "p1"))
                .isInstanceOf(NicknameTakenException.class)
                .hasMessageContaining("已使用昵称");
    }

    @Test
    void lockResolutionPrefersTheExistingIdentityNickname() {
        NicknameClaimStore store = store("claims.json");
        store.claim("demo", "aki", "p1");

        NicknameClaim resolved = store.resolveForLock("demo", "different", "p1");

        assertThat(resolved.nickname()).isEqualTo("aki");
    }

    @Test
    void releaseAllowsAnotherIdentityToClaim() {
        NicknameClaimStore store = store("claims.json");
        NicknameClaim claim = store.claim("demo", "aki", "p1");

        store.release("demo", claim.claimId());

        assertThat(store.claim("demo", "aki", "p2").participantId()).isEqualTo("p2");
        assertThat(store.list("demo")).hasSize(1);
    }

    @Test
    void rebindMovesOwnershipAndRejectsTheOldIdentity() {
        NicknameClaimStore store = store("claims.json");
        NicknameClaim claim = store.claim("demo", "aki", "p1");

        NicknameClaim rebound = store.rebind("demo", claim.claimId(), "p2");

        assertThat(rebound.participantId()).isEqualTo("p2");
        assertThat(rebound.source()).isEqualTo("HOST");
        assertThatThrownBy(() -> store.claim("demo", "aki", "p1"))
                .isInstanceOf(NicknameTakenException.class);
        assertThat(store.claim("demo", "aki", "p2").claimId()).isEqualTo(claim.claimId());
    }

    @Test
    void claimsSurviveStoreReload() {
        String fileName = "claims.json";
        store(fileName).claim("demo", "aki", "p1");

        NicknameClaimStore reloaded = store(fileName);

        assertThat(reloaded.list("demo")).hasSize(1);
        assertThatThrownBy(() -> reloaded.claim("demo", "aki", "p2"))
                .isInstanceOf(NicknameTakenException.class);
    }

    @Test
    void claimsAreScopedByEvent() {
        NicknameClaimStore store = store("claims.json");
        store.claim("demo", "aki", "p1");

        List<NicknameClaim> otherEvent = store.list("other");

        assertThat(otherEvent).isEmpty();
        assertThat(store.claim("other", "aki", "p2").participantId()).isEqualTo("p2");
    }
}
