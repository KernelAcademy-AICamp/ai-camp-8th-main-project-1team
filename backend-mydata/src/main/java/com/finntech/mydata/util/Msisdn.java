package com.finntech.mydata.util;

import java.util.Random;

/**
 * 이동전화 국번(가운데 4자리) 대역표 — 생성기가 <b>실존하는 번호만</b> 만들게 한다.
 *
 * <p><b>왜 필요한가.</b> 예전 생성기는 `010` + 난수 8자리였다. 형식은 맞지만 배정되지 않은 대역이
 * 섞여, 실제로 4,962명 중 1,290명(26%)이 존재하지 않는 번호였다. 온보딩이 국번을 검증하게 되면서
 * 그 사람들은 로그인 자체가 막힌다.
 *
 * <p><b>같은 표가 본체에도 있다</b>({@code com.finntech.util.Msisdn}). 모듈이 분리돼 코드를
 * 공유할 수 없어 한 벌씩 둔다 — <b>한쪽을 고치면 반드시 다른 쪽도 고친다.</b>
 * 본체 쪽에는 통신사 판정까지 있고, 여기는 생성에 필요한 만큼만 둔다.
 *
 * <p>미배정: 0xxx(국번 불가) · 1xxx(전국대표번호 충돌) · 5970~5999 · 6000~6199 · 6900~6999 ·
 * 7000~7099 · 7800~7899.
 */
public final class Msisdn {
    private Msisdn() {}

    /** 배정된 국번 구간 {시작, 끝(포함)}. 합계 7,470개. */
    private static final int[][] ASSIGNED = {
        {2000, 5969},   // 2·3·4천번대 전부 + 5000~5969
        {6200, 6899},
        {7100, 7799},
        {7900, 9999},
    };

    /** 구간 크기 누적합 — 균등 추첨을 위해 미리 계산해 둔다. */
    private static final int TOTAL;
    static {
        int sum = 0;
        for (int[] r : ASSIGNED) sum += r[1] - r[0] + 1;
        TOTAL = sum;
    }

    /**
     * 사람에게 보여줄 표기 — {@code 010-1234-5678}. 11자리가 아니면 준 값 그대로 돌려준다.
     *
     * <p><b>조회의 기준으로 쓰지 않는다.</b> 원장에 쓰는 곳이 둘인데(생성기·실데이터 적재)
     * 표기가 갈려 있어, 이 표기로 정확일치 조회를 하면 한쪽은 있는 사람을 못 찾는다.
     * 실제로 생성기는 처음부터 숫자만으로 저장해 왔는데 본인인증은 이 표기로 찾고 있었고,
     * 그래서 <b>어떤 값을 넣어도</b> "신원 정보가 불일치합니다"가 떴다
     * (2026-08-13 실측 — 로컬 명의자 12명 전원 숫자만 저장. 2026-08-05에 남긴
     * "생성된 4,511명은 전원 하이픈"은 사실과 달랐다).
     * 조회는 {@code MyDataUserRepository#findByPhoneDigits}가 <b>양쪽에서 표기를 빼고</b> 맞춘다.
     */
    public static String format(String phone) {
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        return d.length() == 11 ? d.substring(0, 3) + "-" + d.substring(3, 7) + "-" + d.substring(7) : phone;
    }

    public static boolean isAssigned(int exchange) {
        for (int[] r : ASSIGNED) if (exchange >= r[0] && exchange <= r[1]) return true;
        return false;
    }

    /** 배정된 국번 하나를 균등하게 뽑는다. 구간 길이가 달라 구간을 먼저 고르면 편향된다. */
    public static int randomAssigned(Random rnd) {
        int k = rnd.nextInt(TOTAL);
        for (int[] r : ASSIGNED) {
            int size = r[1] - r[0] + 1;
            if (k < size) return r[0] + k;
            k -= size;
        }
        throw new IllegalStateException("누적합이 어긋났다");   // 도달 불가
    }

    /** 배정 국번 총 개수(테스트가 표의 총량을 확인한다). */
    public static int assignedCount() { return TOTAL; }
}
