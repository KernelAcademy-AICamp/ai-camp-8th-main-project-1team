package com.finntech.mydata.generation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

/**
 * 결정론 시드 유틸(규칙 3: 재현성). 마스터 시드 + 키들을 섞어 하위 시드/RNG를 만든다.
 * {@code now()}·무시드 RNG 금지 — 같은 (마스터시드, 키)면 항상 같은 난수열.
 */
public final class GenSeed {
    private GenSeed() {}

    /** SplitMix64 계열 믹싱으로 (seed, keys)를 하나의 long으로 접는다. */
    public static long mix(long seed, long... keys) {
        long z = seed + 0x9E3779B97F4A7C15L;
        for (long k : keys) {
            z += k * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z = z ^ (z >>> 31);
        }
        return z;
    }

    /** (seed, keys)로 결정론 RNG. */
    public static Random rng(long seed, long... keys) {
        return new Random(mix(seed, keys));
    }

    /** 합성 사용자 CI(64-hex, SHA-256) — 결정론. 실 신원 아님(가상). */
    public static String ci(long seed, long userIndex) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(("gen:" + seed + ":" + userIndex).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 범위 [min,max] 균등 실수. */
    public static double uniform(Random r, double min, double max) {
        return min + r.nextDouble() * (max - min);
    }

    /** 범위 [min,max] 균등 정수(양끝 포함). */
    public static int uniformInt(Random r, int min, int max) {
        return max <= min ? min : min + r.nextInt(max - min + 1);
    }

    /** 평균 1.0 중심의 로그정규 배수(섭동용). sigma 작을수록 1에 가까움. */
    public static double jitter(Random r, double sigma) {
        return Math.exp(r.nextGaussian() * sigma);
    }
}
