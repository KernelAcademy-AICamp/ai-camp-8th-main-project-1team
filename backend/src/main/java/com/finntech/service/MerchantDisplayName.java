package com.finntech.service;

import com.finntech.engine.IndustryCategoryMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <b>소비내역에 무엇을 적을 것인가</b> — 가맹점명 한 줄을 정하는 규칙을 한 곳에 둔다.
 *
 * <h2>왜 필요한가 — PG 를 거친 결제가 29%다</h2>
 *
 * <p>실사용자 결제 2,136건 중 <b>619건(29%)</b>이 PG 사업자번호로 찍힌다(2026-08-26 운영 실측).
 * 그 상호는 {@code 토스페이_일반-(주)비바리퍼블리카}·{@code 정기결제_K-주식회사 퍼플룰러}
 * 처럼 <b>결제 경로가 이름을 밀어낸 꼴</b>이라, 그대로 두면 목록이 읽히지 않는다.
 *
 * <h2>지어내지 않는다 — 표시명은 언제나 원문의 부분집합이다</h2>
 *
 * <p>이 규칙이 정확성을 지탱한다. 우리가 하는 일은 <b>실제 결제처를 알아내는 것이 아니라
 * 확실히 버려도 되는 것만 버리는 것</b>이다. 새 사실을 만들지 않으므로 틀릴 수가 없다.
 * 모델에게 이름을 짓게 하거나 PG 이름을 브랜드 표에 올리면 <b>지어낸 상호가 사실처럼</b>
 * 보이고, 그건 마스터 §4 원칙 1 이 막는 바로 그것이다.
 *
 * <h2>무엇이 확실하고 무엇이 아닌가</h2>
 *
 * <table>
 *   <tr><td>이 결제가 PG 경유인가</td><td><b>확실</b> — 사업자번호가 사실이다</td></tr>
 *   <tr><td>그 PG 가 누구인가</td><td><b>확실</b> — 번호가 지목한다</td></tr>
 *   <tr><td>원문에서 그 PG 이름 지우기</td><td><b>확실</b></td></tr>
 *   <tr><td>남은 것이 가맹점인가 또 다른 결제사인가</td><td><b>불확실</b> — 문자열뿐이다</td></tr>
 * </table>
 *
 * <p>마지막 줄은 <b>자동으로 못 가린다.</b> 실측으로 두 가지를 시도해 봤고 둘 다 실패했다 —
 * <i>"여러 PG 를 탄다"</i> 는 {@code Apple}(PG 2곳)·{@code 카카오}(4곳)가 걸려 못 쓰고,
 * 이름 어미(페이·페이먼츠)는 이미 목록으로 지운 뒤라 남는 것이 없다.
 *
 * <p>그래서 <b>목록을 유한하게 만들어 사람이 훑는다.</b> 남는 잔여 상호는 운영에서 40종뿐이고
 * ({@code /api/admin/dictionary/agency-residue}), 훑어서 결제사면 {@link #EXTRA_AGENCIES} 에,
 * 가맹점이면 브랜드 표에 올린다. <b>잔여 목록은 브랜드 표로 가는 대기열</b>이지 영구 상태가 아니다.
 */
@Service
public class MerchantDisplayName {

    // **선언 순서가 곧 초기화 순서다.** TRADE_KEYS 가 bare() 를 부르므로 패턴이 위에 있어야
    // 한다 — 아래에 두었더니 static 초기화에서 NPE 가 났다(2026-08-26).
    /** 토막 구분자 — <b>공백도 넣는다.</b> 안 넣으면 `네이버페이 네이버` 가 통째로 남는다. */
    private static final Pattern SPLIT = Pattern.compile("[\\s_\\-()（）\\[\\]{}/|,·・]+");
    private static final Pattern LEGAL = Pattern.compile(
            "주식회사|유한회사|유한책임회사|㈜|\\(주\\)|\\(재\\)|\\(사\\)|\\(유\\)");
    private static final Pattern DECOR = Pattern.compile("[\\s()（）\\[\\]{}·・/\\\\|,\\-_.]");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** 표시명을 무엇으로 정했는가 — 화면이 배지·펼침을 가르는 근거다. */
    public enum Source {
        /** 표기표가 확정한 브랜드. 사람이 검수한 값이라 가장 믿을 만하다. */
        BRAND,
        /** PG·업태를 걷어내고 <b>원문에 남은</b> 상호. 지어낸 것이 아니다. */
        RESIDUE,
        /** 걷어내니 아무것도 안 남았다 — 카드사가 준 정보가 "간편결제로 샀다" 뿐이다. */
        AGENCY_ONLY,
        /** PG 가 안 섞인 보통의 상호. 원문 그대로다. */
        RAW,
        /**
         * <b>규칙으로는 못 줄여 모델에게 맡긴 이름</b> — 최후의 수단이다.
         *
         * <p>모델은 <b>글자만</b> 만진다. 받은 답은 원문에 없는 낱말이 섞이면 무료 통로가
         * 버린다({@code keepsWords}). 출처를 갈라 두는 것이 요점이다 —
         * 규칙이 정한 이름과 모델이 줄인 이름을 한 칸에 섞으면 나중에 가려낼 수 없다.
         */
        MODEL_SHORT
    }

    /**
     * 이 길이를 넘으면 <b>목록에서 읽히지 않는다</b> — 최후의 수단을 부르는 문턱.
     *
     * <p>운영 실측(2026-08-26): 규칙을 다 거친 뒤 이 길이를 넘는 것은 실사용자 상호
     * <b>15종</b>뿐이고 그중 대부분이 해외 결제다. 문턱을 낮추면 멀쩡한 상호까지 모델에게
     * 가고, 높이면 긴 것이 그대로 남는다.
     */
    public static final int TOO_LONG = 14;

    /**
     * @param display   화면에 적을 이름
     * @param source    무엇으로 정했나
     * @param viaAgency 거쳐 간 결제대행사. PG 경유가 아니면 {@code null}
     */
    public record Shown(String display, Source source, String viaAgency) {}

    private final IndustryCategoryMapper mapper;

    public MerchantDisplayName(IndustryCategoryMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * <b>표시명을 정한다.</b>
     *
     * @param merchantName   원문 가맹점명
     * @param businessNumber 결제에 실린 사업자번호. PG 여부·이름이 여기서 나온다
     * @param brand          표기표가 확정한 브랜드. 없으면 {@code null}
     */
    public Shown of(String merchantName, String businessNumber, String brand) {
        String raw = merchantName == null ? "" : merchantName.trim();
        String agency = mapper.paymentAgencyOf(businessNumber);
        String via = agency.isEmpty() ? null : shownAgency(agency);

        if (brand != null && !brand.isBlank()) {
            return new Shown(brand.trim(), Source.BRAND, via);
        }
        if (raw.isEmpty()) {
            return new Shown(via == null ? "" : via,
                    via == null ? Source.RAW : Source.AGENCY_ONLY, via);
        }
        // 번호가 PG 가 아니어도 이름에 PG 가 섞여 있을 수 있다 — 실데이터에 흔하다.
        String residue = residue(raw, agency);
        if (residue.equals(squashSpaces(raw))) {
            return new Shown(raw, Source.RAW, via);      // 걷어낸 것이 없다
        }
        if (!residue.isEmpty()) {
            return new Shown(residue, Source.RESIDUE, via);
        }
        // **아무것도 안 남았다.** 결제 경로 이름을 짧게 적고 가맹점이 아님을 배지로 말한다 —
        // 긴 원문을 그대로 두면 결제수단이 가게처럼 보인다.
        return new Shown(via != null ? via : raw, via != null ? Source.AGENCY_ONLY : Source.RAW, via);
    }

    /**
     * <b>PG·업태를 걷어낸 나머지</b> — 남는 토막을 원문 표기 그대로 이어 붙인다.
     *
     * <p>{@code MerchantClassifierService.residueOf} 와 <b>쓰임이 다르다.</b> 저쪽은 <i>"물어볼
     * 값이 있는가"</i>를 판정하려고 대문자화·공백제거한 <b>비교용 키</b>를 만든다. 화면에 그
     * 값을 쓰면 {@code 정기결제_K-주식회사 퍼플룰러} 가 {@code K퍼플룰러} 로 보인다.
     */
    String residue(String name, String agency) {
        List<String> kept = new ArrayList<>();
        List<Boolean> aside = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean[] inParen = parenMask(name);
        int at = 0;
        for (String piece : SPLIT.split(name)) {
            int found = piece.isEmpty() ? -1 : name.indexOf(piece, at);
            if (found >= 0) at = found + piece.length();
            boolean parenthesised = found >= 0 && inParen[found];
            String token = LEGAL.matcher(piece).replaceAll("").trim();
            String key = bare(token);
            if (key.isEmpty() || key.length() < 2 || DIGITS.matcher(key).matches()) continue;
            if (TRADE_KEYS.contains(key)) continue;

            // **토막 안쪽까지 걷어낸다.** `NICE결제대행` 처럼 구분자 없이 붙어 오는 것이 있다.
            String rest = key;
            for (String form : agencyForms()) rest = rest.replace(form, "");
            // **두 글자 미만은 부분 삭제하지 않는다.** 업태어 `구` 가 `구글` 을 `글` 로 만들어
            // 멀쩡한 상호를 지웠다(2026-08-26 실측). 짧은 낱말은 토막째로만 본다.
            for (String word : TRADE_KEYS) if (word.length() >= 2) rest = rest.replace(word, "");
            if (rest.length() < 2) continue;
            // **잘린 PG 이름도 PG 다** — `(주)비바리퍼블리` 는 `비바리퍼블리카` 가 잘린 것이다.
            // 네 글자 이상일 때만 본다. 짧게 잡으면 `카카오`(회사)가 `카카오페이`(결제수단)에
            // 먹혀 진짜 회사가 사라진다(2026-08-26 실측: 주식회사 카카오 18건).
            if (rest.length() >= 4 && isAgencyPrefix(rest)) continue;
            // **앞 토막과 겹치는 뒷 토막은 버린다** — `무신사-무신사페이먼츠-무신사페이` 가
            // 세 번 나열되던 자리다. 품는 쪽·품기는 쪽 <b>양쪽</b>을 본다:
            // `CJ더마켓 CJ제일제당 더마켓` 의 마지막 `더마켓` 은 앞 토막 안에 들어 있다.
            if (kept.stream().anyMatch(prev -> {
                String before = bare(prev);
                return key.contains(before) || before.contains(key);
            })) continue;
            if (seen.add(key)) { kept.add(token); aside.add(parenthesised); }
        }
        // **괄호 안은 부연이다.** 본문이 있으면 화면에서 접는다 —
        // `핑크고릴라커피(PINK GORILLA COFFEE)` 는 같은 이름을 두 언어로 적은 것이고,
        // `주식회사 우리들곳간(해피베네핏 성수점)` 은 지점 설명이다. 원문은 눌러서 본다.
        boolean hasBody = aside.contains(Boolean.FALSE);
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            if (hasBody && aside.get(i)) continue;
            shown.add(kept.get(i));
        }
        return String.join(" ", trim(shown)).trim();
    }

    /**
     * <b>핵심만 남긴다</b> — 운영사와 지점명을 접는다.
     *
     * <p>둘 다 <b>토막이 여럿일 때만</b> 접는다. 하나뿐이면 그것이 이름 전체라
     * {@code 친절한정육점}·{@code 쿨링쿨링아이스크림할인점남현점} 이 통째로 사라진다.
     *
     * <p>원문은 버리지 않는다 — 화면에서 '원문'을 눌러 본다.
     */
    private static List<String> trim(List<String> tokens) {
        List<String> out = new ArrayList<>(tokens);
        // ① 앞의 <b>운영사</b> — `에이치디씨현대산업개발 고척아이파크쇼핑센터` 에서
        //    돈을 쓴 곳은 뒤다. 국세청에 등록된 법인이 앞에 붙어 오는 꼴이다.
        while (out.size() >= 2 && OPERATOR_TAIL.stream().anyMatch(out.get(0)::endsWith)) {
            out.remove(0);
        }
        // ② 뒤의 <b>지점명</b> — `세븐틴코인노래연습장 성신여대역점` 에서 어느 지점인지는
        //    목록에서 필요 없다. 같은 가게가 지점마다 다른 줄로 보이는 것을 막는다.
        while (out.size() >= 2 && out.get(out.size() - 1).endsWith("점")) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /**
     * 앞에 붙는 <b>운영사 이름의 꼬리</b>. 결제대행사는 아니지만 <i>"어디서 썼나"</i>를
     * 말해 주지 않는다 — 프랜차이즈 본부·유통 법인이 상호 앞에 실려 오는 자리다.
     *
     * <p>목록을 늘릴 때 묻는 것은 하나다 — <i>"이 꼬리로 끝나는 이름이 가게 이름일 수 있는가."</i>
     * {@code 백화점}·{@code 마트} 는 가게라 여기 넣으면 안 된다.
     */
    private static final List<String> OPERATOR_TAIL = List.of(
            "산업개발", "네트웍스", "리테일", "웰푸드", "홀딩스", "파트너스", "커뮤니케이션즈",
            "타임그룹", "물산", "유통");

    /** 글자마다 <b>괄호 안인가</b>. 여는 괄호를 세어 중첩도 함께 본다. */
    private static boolean[] parenMask(String name) {
        boolean[] mask = new boolean[name.length()];
        int depth = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '(' || c == '（' || c == '[' || c == '{') { depth++; mask[i] = true; continue; }
            if (c == ')' || c == '）' || c == ']' || c == '}') { mask[i] = true; if (depth > 0) depth--; continue; }
            mask[i] = depth > 0;
        }
        return mask;
    }

    /**
     * <b>화면에 적을 결제대행사 이름.</b> 대조표는 번호를 가리려고 만든 것이라 이름에
     * {@code (제2번호)} 같은 <b>내부 주석</b>이 붙어 있다 — 그대로 내보내면 사용자가 본다.
     */
    private static String shownAgency(String agency) {
        int at = agency.indexOf('(');
        String cut = at > 0 ? agency.substring(0, at) : agency;
        return cut.replace("㈜", "").replace("(주)", "").trim();
    }

    /** 잔여가 알려진 결제대행사 이름의 <b>앞부분</b>인가 — 잘려 들어온 PG 를 잡는다. */
    private boolean isAgencyPrefix(String rest) {
        for (String form : agencyForms()) {
            if (form.length() > rest.length() && form.startsWith(rest)) return true;
        }
        return false;
    }

    /** 상호에서 걷어낼 결제대행 표기 — 긴 것부터 지운다(`토스페이`가 `토스페이먼츠`를 깎으면 `먼츠`가 남는다). */
    private List<String> agencyForms() {
        Set<String> forms = new LinkedHashSet<>();
        for (String name : mapper.paymentAgencyNames()) forms.add(bare(name));
        for (String alias : EXTRA_AGENCIES) forms.add(bare(alias));
        List<String> out = new ArrayList<>(forms);
        out.removeIf(String::isEmpty);
        out.sort((a, b) -> b.length() - a.length());
        return out;
    }

    /**
     * 대조표({@code pg-사업자번호.tsv})에 없는 <b>결제대행·중개 표기</b>.
     *
     * <p>대조표는 <b>사업자번호</b>로 PG 를 가리려고 만든 것이라 법인명만 있다. 그런데
     * 명세서에 찍히는 것은 서비스명이거나(네이버페이·NICE) <b>2차 대행사</b>다 —
     * 카드사가 준 번호는 KIS 인데 상호는 {@code (주)발트페이먼츠} 인 결제가 13건 있었다
     * (2026-08-26 실측).
     *
     * <p><b>목록을 늘릴 때 묻는 것은 하나다</b> — <i>"이 이름만으로 무엇을 샀는지 말할 수
     * 있는가."</i> 못 하면 여기 넣는다. 새 이름은 잔여 목록 문이 알려 준다.
     */
    static final List<String> EXTRA_AGENCIES = List.of(
            "네이버페이", "토스페이", "카카오선물하기", "삼성페이", "애플페이", "페이코", "PAYCO",
            "엔에이치엔페이코", "스마트로", "KCP", "한국사이버결제", "NICE", "나이스",
            "나이스인프라", "나이스정보통신", "KIS", "올더게이트", "세틀뱅크", "다우데이타",
            "한국신용카드결제", "갤럭시아", "모빌리언스", "스토리페이", "당근페이", "이니시스",
            "웰컴페이먼츠", "비바리퍼블리카", "발트페이먼츠", "코페이", "기프티스타", "결제대행",
            "전자결제", "이지웰");

    /**
     * PG 를 뺀 자리에 남는 <b>업태·안내 문구</b> — 가맹점 이름이 아니다.
     *
     * <p>여기 있는 낱말만 남았다면 카드사가 준 정보는 "간편결제로 무언가를 샀다"뿐이다.
     */
    static final List<String> TRADE_WORDS = List.of(
            "통신판매", "비인증", "일반", "오더", "결제", "쇼핑몰", "온라인", "정기결제", "자동이체",
            "상품권", "충전", "선불", "간편결제", "휴대폰", "계좌이체", "가맹점", "KIOSK", "POS",
            "선물하기", "교환권", "소득공제", "인터넷상거래", "주문", "정보통신", "문화비",
            "정기과금", "자동결제", "매입", "승인", "취소");

    private static final Set<String> TRADE_KEYS = new LinkedHashSet<>(TRADE_WORDS.stream()
            .map(MerchantDisplayName::bare).filter(s -> !s.isEmpty()).toList());

    private static String bare(String s) {
        return s == null ? "" : DECOR.matcher(LEGAL.matcher(s).replaceAll("")).replaceAll("").toUpperCase();
    }

    private static String squashSpaces(String s) {
        return String.join(" ", s.trim().split("\\s+"));
    }
}
