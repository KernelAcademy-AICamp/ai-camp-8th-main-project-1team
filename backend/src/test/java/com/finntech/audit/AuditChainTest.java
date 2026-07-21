package com.finntech.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** 감사로그 무결성 — 성공 지표 "감사로그 임의 변조 시 100% 탐지"의 근거. */
class AuditChainTest {

    @Test
    @DisplayName("정규화 JSON은 키 삽입 순서와 무관하게 같은 문자열을 낸다")
    void canonicalJsonIsOrderIndependent() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("z", 1);
        a.put("a", "x");
        a.put("m", true);

        Map<String, Object> b = new TreeMap<>();
        b.put("a", "x");
        b.put("m", true);
        b.put("z", 1);

        assertEquals(CanonicalJson.write(a), CanonicalJson.write(b));
        assertEquals("{\"a\":\"x\",\"m\":true,\"z\":1}", CanonicalJson.write(a));
    }

    @Test
    @DisplayName("정규화 JSON은 따옴표·역슬래시·개행을 이스케이프한다")
    void canonicalJsonEscapes() {
        String out = CanonicalJson.write(Map.of("k", "a\"b\\c\nd"));
        assertEquals("{\"k\":\"a\\\"b\\\\c\\nd\"}", out);
    }

    @Test
    @DisplayName("리프와 내부 노드는 도메인 분리되어 서로 다른 해시를 낸다 (RFC 6962)")
    void domainSeparation() {
        String h = "ab".repeat(32);
        // 접두사가 없으면 leafHash(h)와 nodeHash가 충돌할 수 있다.
        assertNotEquals(Hashing.leafHash(h), Hashing.nodeHash(h, h));
    }

    @Test
    @DisplayName("Merkle 루트는 입력이 하나만 바뀌어도 달라진다")
    void merkleRootIsSensitive() {
        List<String> a = List.of("11".repeat(32), "22".repeat(32), "33".repeat(32));
        List<String> b = List.of("11".repeat(32), "22".repeat(32), "34".repeat(32));
        assertNotEquals(Hashing.merkleRoot(a), Hashing.merkleRoot(b));
    }

    @Test
    @DisplayName("Merkle 루트는 같은 입력에 항상 같은 값을 낸다")
    void merkleRootIsDeterministic() {
        List<String> hashes = List.of("11".repeat(32), "22".repeat(32),
                "33".repeat(32), "44".repeat(32), "55".repeat(32));
        assertEquals(Hashing.merkleRoot(hashes), Hashing.merkleRoot(hashes));
    }

    @Test
    @DisplayName("페이로드를 한 글자만 바꿔도 entry_hash가 달라진다 — 변조 탐지")
    void tamperingChangesEntryHash() {
        String prev = Hashing.ZERO_HASH;
        String original = CanonicalJson.write(Map.of("riskScore", "3.51"));
        String tampered = CanonicalJson.write(Map.of("riskScore", "3.50"));

        assertNotEquals(Hashing.entryHash(prev, original), Hashing.entryHash(prev, tampered));
    }
}
