package com.finntech.mydata.generation;

import java.util.Random;

/**
 * 사업자등록번호(10자리) 결정론 생성·검증 유틸. 순수 함수(테스트 용이·재현성 §3).
 *
 * <p>형식 {@code XXX-YY-ZZZZA}: XXX=200~899, YY=01~79, ZZZZ=0001~9999, A=검증숫자.
 * 검증숫자 A는 국세청 사업자등록번호 검증 공식(가중치 {1,3,7,1,3,7,1,3,5} + 9번째 자리 특수처리)으로 부여한다.
 * 같은 seed → 같은 번호. seed는 가맹점 정규 신원(이름+동)에서 파생한다(가맹점 신원↔번호 고정 매핑).
 */
public final class BusinessNumberGenerator {

    private static final int[] WEIGHTS = {1, 3, 7, 1, 3, 7, 1, 3, 5};

    private BusinessNumberGenerator() {}

    /**
     * seed로 결정론 사업자등록번호 10자리(하이픈 없음)를 만든다.
     * XXX∈[200,899], YY∈[1,79], ZZZZ∈[1,9999], 마지막 자리는 검증숫자.
     */
    public static String generate(long seed) {
        Random r = new Random(seed);
        int xxx = 200 + r.nextInt(700);   // 200 ~ 899
        int yy = 1 + r.nextInt(79);       // 01 ~ 79
        int zzzz = 1 + r.nextInt(9999);   // 0001 ~ 9999

        int[] d = new int[10];
        d[0] = xxx / 100; d[1] = (xxx / 10) % 10; d[2] = xxx % 10;
        d[3] = yy / 10;   d[4] = yy % 10;
        d[5] = zzzz / 1000; d[6] = (zzzz / 100) % 10; d[7] = (zzzz / 10) % 10; d[8] = zzzz % 10;
        d[9] = checkDigit(d);

        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) sb.append(d[i]);
        return sb.toString();
    }

    /**
     * 앞 9자리로부터 검증숫자를 계산한다(국세청 공식).
     * sum = Σ(앞9자리 × 가중치) + (9번째자리 × 5) / 10, 검증숫자 = (10 − sum%10) % 10.
     */
    static int checkDigit(int[] d) {
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += d[i] * WEIGHTS[i];
        sum += (d[8] * 5) / 10;   // 9번째 자리 특수처리(정수 나눗셈 — 십의 자리 올림 반영)
        return (10 - (sum % 10)) % 10;
    }

    /** 사업자등록번호 유효성(하이픈 허용). 검증숫자가 공식과 일치하는지 확인한다. */
    public static boolean isValid(String businessNumber) {
        if (businessNumber == null) return false;
        String digits = businessNumber.replaceAll("[^0-9]", "");
        if (digits.length() != 10) return false;
        int[] d = new int[10];
        for (int i = 0; i < 10; i++) d[i] = digits.charAt(i) - '0';
        return checkDigit(d) == d[9];
    }

    /** 표시용 포맷 {@code XXX-YY-ZZZZA}. 입력은 하이픈 없는 10자리. */
    public static String format(String tenDigits) {
        if (tenDigits == null || tenDigits.length() != 10) {
            throw new IllegalArgumentException("사업자등록번호는 10자리여야 합니다: " + tenDigits);
        }
        return tenDigits.substring(0, 3) + "-" + tenDigits.substring(3, 5) + "-" + tenDigits.substring(5);
    }
}
