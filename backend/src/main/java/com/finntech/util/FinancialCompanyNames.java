package com.finntech.util;

import java.util.Map;

/**
 * 금융회사명 정규화 — <b>같은 금융그룹인가</b>를 가리는 데만 쓴다.
 *
 * <p><b>왜 필요한가.</b> 저축 상품의 우대조건 28%가 `당행`을 건다(§4.5 M6 ④ 실측). 그 조건을 판정하려면
 * 상품을 파는 금융사와 사용자의 계좌·카드가 같은 곳인지 봐야 하는데, <b>표기가 출처마다 다르다.</b>
 *
 * <pre>
 *   금감원 공시   농협은행주식회사 · 주식회사 하나은행 · 주식회사 케이뱅크 · 중소기업은행
 *   마이데이터     NH농협은행 · 하나카드 · KB국민카드 · 우리카드
 * </pre>
 *
 * 문자열을 그대로 비교하면 `국민은행` 적금의 당행 조건을 `KB국민카드`로 채운 사용자가 미충족으로 나온다.
 *
 * <p><b>은행과 카드를 같은 그룹으로 본다.</b> `은행`·`카드`·`뱅크`를 떼는 이유가 그것이다 — 우대조건이
 * `우리카드사 신용/체크카드 결제`처럼 <b>계열 카드사</b>를 가리키는 경우가 흔하다.
 *
 * <p><b>모르면 빈 문자열을 낸다.</b> 판정을 강행하지 않기 위해서다 — 정규화가 실패한 이름으로 비교하면
 * 엉뚱한 <b>미충족을 확정</b>하게 된다. 호출부(M6)는 빈 값을 만나면 <b>판정 불가</b>로 넘긴다.
 *
 * <p>순수 함수라 단위 테스트가 가능하고 상태가 없다.
 */
public final class FinancialCompanyNames {

    private FinancialCompanyNames() {}

    /**
     * 약칭 → 그룹명. 정규화 <b>뒤</b>의 값에 적용한다.
     *
     * <p>여기 없는 이름은 알아서 축약된 값을 그대로 쓴다 — 표를 전수로 채우려 들면 새 금융사가 생길 때마다
     * 코드를 고쳐야 하고, 대부분은 축약만으로도 맞는다(`우리은행`·`우리카드` → `우리`).
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("kb", "국민"),
            Map.entry("kb국민", "국민"),
            Map.entry("nh", "농협"),
            Map.entry("nh농협", "농협"),
            Map.entry("ibk", "기업"),
            Map.entry("중소기업", "기업"),
            Map.entry("sc", "스탠다드차타드"),
            Map.entry("sc제일", "스탠다드차타드"),
            Map.entry("스탠다드차타드", "스탠다드차타드"),
            Map.entry("sh", "수협"),
            Map.entry("sh수협", "수협"),
            Map.entry("kdb", "산업"),
            Map.entry("dgb", "대구"),
            Map.entry("im", "대구"),          // iM뱅크 = 옛 대구은행
            Map.entry("jb", "전북"),
            Map.entry("bnk부산", "부산"),
            Map.entry("bnk경남", "경남"),
            Map.entry("k", "케이"),
            Map.entry("kj", "광주"));

    /** 법인 표기·업종어처럼 회사를 가리지 않는 토막. 길이가 긴 것부터 지운다. */
    private static final String[] NOISE = {
            "주식회사", "(주)", "㈜", "한국", "은행", "카드사", "카드", "뱅크", "금융지주", "지주", " "};

    /**
     * 표기가 다른 금융회사명을 그룹 이름으로 줄인다. 알아볼 수 없으면 <b>빈 문자열</b>.
     *
     * <pre>
     *   농협은행주식회사 → 농협      KB국민카드 → 국민      주식회사 케이뱅크 → 케이
     *   주식회사 하나은행 → 하나      중소기업은행 → 기업     한국스탠다드차타드은행 → 스탠다드차타드
     * </pre>
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase().trim();
        for (String noise : NOISE) {
            s = s.replace(noise.toLowerCase(), "");
        }
        s = s.trim();
        if (s.isEmpty()) return "";
        String alias = ALIASES.get(s);
        return alias != null ? alias : s;
    }

    /**
     * 두 금융회사명이 같은 그룹인가. <b>한쪽이라도 알아볼 수 없으면 {@code null}</b> —
     * `아니다`가 아니라 `모른다`이며, 호출부가 그 둘을 구분해야 한다.
     */
    public static Boolean sameGroup(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return null;
        return na.equals(nb);
    }

    /**
     * 이 금융사가 목록 안에 있는가. <b>{@code target}을 알아볼 수 없으면 {@code null}(모름)</b>이다.
     *
     * <p><b>목록이 비어 있으면 {@code false}(없다)</b>이지 모름이 아니다. "재료를 못 받았다"는 사실은
     * 이 함수가 아니라 {@code Preferential.known()}이 들고 있으므로, 호출부가 그걸 먼저 보고 들어온다 —
     * 여기까지 왔으면 목록이 빈 것은 <b>해당하는 금융사가 하나도 없다</b>는 뜻이다.
     */
    public static Boolean containsGroup(Iterable<String> candidates, String target) {
        String nt = normalize(target);
        if (nt.isEmpty()) return null;
        for (String c : candidates) {
            if (nt.equals(normalize(c))) return true;
        }
        return false;
    }
}
