package com.finntech.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 해시체인·Merkle 트리 (문서 §5-4).
 *
 * <p><b>도메인 분리 필수</b> (구현 함정 2, RFC 6962): 리프는 {@code 0x00},
 * 내부 노드는 {@code 0x01} 접두사를 붙인다. 안 하면 리프 해시를 내부 노드로 위장해
 * <b>트리 구조를 속일 수 있다</b>(second-preimage).
 */
public final class Hashing {

    public static final String ZERO_HASH = "0".repeat(64);
    private static final byte LEAF_PREFIX = 0x00;
    private static final byte NODE_PREFIX = 0x01;

    private Hashing() {}

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static byte[] unhex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** entry_hash(n) = SHA256(prev_hash || canonical_json(entry_n)) */
    public static String entryHash(String prevHash, String canonicalPayload) {
        MessageDigest md = sha256();
        md.update(prevHash.getBytes(StandardCharsets.UTF_8));
        md.update(canonicalPayload.getBytes(StandardCharsets.UTF_8));
        return hex(md.digest());
    }

    public static String leafHash(String entryHashHex) {
        MessageDigest md = sha256();
        md.update(LEAF_PREFIX);
        md.update(unhex(entryHashHex));
        return hex(md.digest());
    }

    public static String nodeHash(String left, String right) {
        MessageDigest md = sha256();
        md.update(NODE_PREFIX);
        md.update(unhex(left));
        md.update(unhex(right));
        return hex(md.digest());
    }

    /**
     * Merkle 루트. 홀수 개일 때 마지막 노드를 자기 자신과 짝지어 올린다
     * (RFC 6962는 홀수 노드를 그대로 승격시키지만, 여기서는 구현 단순성을 위해 복제 방식을 쓰고
     * 검증기도 동일 규칙을 쓰므로 일관된다).
     */
    public static String merkleRoot(List<String> entryHashes) {
        if (entryHashes.isEmpty()) return ZERO_HASH;
        List<String> level = new ArrayList<>(entryHashes.size());
        for (String h : entryHashes) level.add(leafHash(h));

        while (level.size() > 1) {
            List<String> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i < level.size(); i += 2) {
                String l = level.get(i);
                String r = (i + 1 < level.size()) ? level.get(i + 1) : l;
                next.add(nodeHash(l, r));
            }
            level = next;
        }
        return level.get(0);
    }
}
