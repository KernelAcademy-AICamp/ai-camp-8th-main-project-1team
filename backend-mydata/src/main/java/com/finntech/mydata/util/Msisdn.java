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
     * <p><b>지문의 정규화 기준이 이 함수다.</b> {@code UserIdentityIndex#ofPhone} 이 지문을
     * 만들기 전에 여기를 거치므로, <b>쓰는 쪽과 찾는 쪽이 한 벌</b>이 된다. 원장에 어떤 표기로
     * 앉아 있든({@code 01012345678} 이든 {@code 010-1234-5678} 이든) 같은 지문이 나온다.
     *
     * <p>이 한 벌이 깨지면 <b>있는 사람을 영원히 못 찾는다.</b> 실제로 그런 적이 있다 —
     * 조회만 하이픈 표기로 정확일치를 걸어 두어, 원장에 숫자만으로 앉은 사람은 자기 번호를
     * 정확히 넣고도 "신원 정보가 불일치합니다"를 들었다(2026-08-13 실측 — 로컬 명의자 12명
     * 전원 숫자만 저장. 2026-08-05에 남긴 "생성된 4,511명은 전원 하이픈"은 사실과 달랐다).
     * 그래서 지문 방식으로 옮긴 지금도 <b>정규화는 반드시 이 함수 하나만</b> 쓴다.
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
