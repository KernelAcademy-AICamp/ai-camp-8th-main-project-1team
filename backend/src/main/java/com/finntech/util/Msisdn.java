package com.finntech.util;

/**
 * 이동전화 국번(가운데 4자리)이 어느 통신사에 배정된 대역인지 판정한다.
 *
 * <p><b>왜 필요한가.</b> 온보딩은 통신사를 고르게 해 놓고 그 값을 쓰지 않았고, 번호는 형식
 * (`010-****-****`)만 봤다. 형식이 맞아도 **없는 번호일 수 있다** — 실제로 생성 신원 4,962명 중
 * 1,290명(26%)이 미배정 국번이었다. 여기서 국번을 실제 대역표로 판정해, 실존하지 않는 번호와
 * 통신사 불일치를 온보딩에서 걸러낸다.
 *
 * <p><b>표는 여기 하나뿐이다.</b> 프론트에도 같은 표를 두면 반드시 어긋나므로, 판정은 서버가 하고
 * 화면은 사유만 표시한다(마스터 §4 원칙 1 — 판단은 설명가능한 코드가).
 * 다만 {@code backend-mydata}는 별도 모듈이라 코드를 공유할 수 없어 같은 표가 한 벌 더 있다
 * ({@code com.finntech.mydata.util.Msisdn}). <b>한쪽을 고치면 반드시 다른 쪽도 고친다.</b>
 *
 * <p><b>미배정 구간과 사유</b> — 0xxx는 국번이 될 수 없고, 1xxx는 전국대표번호(1544·1600 등)와
 * 충돌해 배정하지 않는다. 나머지(5970~5999·6000~6199·6900~6999·7000~7099·7800~7899)는
 * 대역표상 비어 있다.
 */
public final class Msisdn {
    private Msisdn() {}

    /** 통신사. 알뜰폰(MVNO)은 3사 대역을 빌려 쓰므로 별도 대역이 없다 — {@link #matches} 참고. */
    public enum Carrier {
        SKT("SKT"), KT("KT"), LGU("LG U+");

        private final String label;
        Carrier(String label) { this.label = label; }
        /** 화면에 그대로 보여줄 이름. */
        public String label() { return label; }
    }

    /** 사용자가 고를 수 있는 알뜰폰 표기 — 이 값이면 대역을 따지지 않는다. */
    public static final String MVNO = "알뜰폰";

    // 구간은 {시작, 끝(포함)} 이며 서로 겹치지 않는다. 합계 7,470개(SKT 3,250·KT 2,520·LGU+ 1,700).
    private static final int[][] SKT_RANGES = {
        {2000, 2179}, {3100, 3199}, {3500, 3899}, {4000, 4199}, {4500, 4999},
        {5000, 5099}, {5200, 5499}, {5900, 5969}, {6200, 6499}, {7100, 7199},
        {8500, 8999}, {9000, 9499},
    };
    private static final int[][] KT_RANGES = {
        {2180, 2199}, {2500, 2999}, {3000, 3099}, {3200, 3499}, {4200, 4499},
        {5100, 5199}, {6500, 6899}, {7200, 7499}, {9500, 9999},
    };
    private static final int[][] LGU_RANGES = {
        {2200, 2499}, {3900, 3999}, {5500, 5899}, {7500, 7799}, {7900, 7999},
        {8000, 8499},
    };

    /**
     * 국번이 속한 통신사. 미배정이면 {@code null}이다.
     *
     * @param exchange 가운데 4자리(0~9999). 범위 밖이면 미배정으로 본다.
     */
    public static Carrier carrierOf(int exchange) {
        if (in(SKT_RANGES, exchange)) return Carrier.SKT;
        if (in(KT_RANGES, exchange)) return Carrier.KT;
        if (in(LGU_RANGES, exchange)) return Carrier.LGU;
        return null;
    }

    /** 휴대폰 번호 문자열에서 국번을 뽑아 판정한다. 하이픈 유무는 상관없다. 길이가 안 맞으면 null. */
    public static Carrier carrierOfPhone(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.length() != 11) return null;
        return carrierOf(Integer.parseInt(digits.substring(3, 7)));
    }

    /**
     * 고른 통신사가 그 번호의 대역과 맞는가.
     *
     * <p>알뜰폰은 3사 망을 모두 빌려 쓰므로 <b>유효한 국번이기만 하면 통과</b>시킨다.
     * 알뜰폰 가입자의 번호만 보고 원 사업자를 되짚을 수는 있지만, 사용자가 자기 알뜰폰의
     * 모회사망을 알 이유가 없다.
     */
    public static boolean matches(String selectedCarrier, Carrier actual) {
        if (actual == null) return false;                 // 미배정 번호는 애초에 통과시키지 않는다
        if (MVNO.equals(selectedCarrier)) return true;
        return actual.label().equals(selectedCarrier);
    }

    private static boolean in(int[][] ranges, int n) {
        for (int[] r : ranges) if (n >= r[0] && n <= r[1]) return true;
        return false;
    }
}
