package com.finntech.service;

import com.finntech.config.IndustryLookupProperties;
import com.finntech.engine.IndustryCategoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 분류 순위 <b>②-b</b> — 사업자등록번호로 <b>등록 업종</b>을 물어 중분류를 정한다.
 *
 * <pre>
 *   ① merchant_category (확정·추정 모두 — 두 번 묻지 않는다)
 *   ② 업종코드 대조표            ← 실 명세서에는 업종코드가 없어 대개 못 쓴다
 *   ②-b 여기 — 등록 업종 조회      ← 사실이라 LLM 보다 위다
 *   ③ LLM 추정 (표시만)
 *   ④ 카테고리없음
 * </pre>
 *
 * <p><b>왜 LLM 위인가.</b> 여기서 얻는 것은 국세청에 등록된 업종이라 <b>사실</b>이고, 축 배정은
 * 사람이 검토한 대조표가 한다. 모델의 기억이 아니라 등기부에 가까운 값이다. 정답을 이미 아는
 * 가맹점 28곳에 걸어 본 실측에서 <b>붙은 20건이 전부 맞았다</b>(2026-08-07).
 *
 * <p><b>세 겹의 방어벽을 지난다 — 하나라도 걸리면 묻지 않는다.</b> 등록 업종은 "이 사업자가
 * 무슨 일을 하는가"이지 "이 결제가 무엇에 쓴 돈인가"가 아니라, 둘이 어긋나는 자리에서는
 * 답이 있어도 써서는 안 된다.
 *
 * <ul>
 *   <li><b>PG·간편결제</b> — 번호가 남의 것이다. KG모빌리언스를 물으면 에버랜드 결제가
 *       정보서비스업이 된다.</li>
 *   <li><b>복합 사업자</b> — 번호는 맞지만 성격이 다른 가게가 여럿 붙어 있다.</li>
 *   <li><b>관측상 상호가 여럿인 번호</b>({@code business_number_kind}, V16) — 한 번호가
 *       앱스토어와 OTT 를 동시에 달고 있으면 번호 하나의 업종으로 칠할 수 없다. 실제로
 *       애플코리아 번호에는 상호가 4개(앱스토어·애플TV+…) 붙어 있다.</li>
 * </ul>
 *
 * <p>그래도 새는 자리가 있다: <b>상호가 하나뿐인 간편결제</b>는 관측 판정에 잡히지 않는다
 * (삼성페이는 언제나 '삼성페이'로만 찍힌다). 그건 명단으로 막는다
 * ({@code scripts/industry/pg-사업자번호.tsv}).
 *
 * <p><b>없어도 되는 통로다.</b> 꺼져 있거나(기본값) 조회처가 답을 안 주거나 답한 업종이
 * 대조표에 없으면 {@link Optional#empty()} 를 주고 분류는 ③으로 내려간다. 그래서 이 클래스는
 * 어떤 예외도 밖으로 던지지 않는다 — 남의 서버 사정으로 연동이 실패하면 안 된다.
 */
@Service
public class IndustryLookupService {

    private static final Logger log = LoggerFactory.getLogger(IndustryLookupService.class);

    private final IndustryLookupProperties props;
    private final IndustryCategoryMapper mapper;
    private final BusinessNumberKindService kinds;
    private final RestClient client;
    private final Pattern extractor;

    public IndustryLookupService(IndustryLookupProperties props,
                                 IndustryCategoryMapper mapper,
                                 BusinessNumberKindService kinds) {
        this.props = props;
        this.mapper = mapper;
        this.kinds = kinds;
        this.client = props.usable()
                ? RestClient.builder()
                        .requestFactory(factory(props.getTimeoutMs()))
                        .defaultHeader("Accept-Language", "ko-KR,ko;q=0.9")
                        .build()
                : null;
        Pattern p = null;
        if (props.usable()) {
            try {
                p = Pattern.compile(props.getPattern(), Pattern.DOTALL);
            } catch (RuntimeException e) {
                // 정규식이 깨졌다고 기동을 막지는 않는다 — 이 통로는 없어도 되는 것이라
                // 막으면 "있으면 좋은 것" 때문에 서비스가 안 뜬다. 대신 꺼진 채로 간다.
                log.warn("업종 조회 추출식이 올바르지 않아 이 통로를 끈다: {}", e.getMessage());
            }
        }
        this.extractor = p;
        if (usable()) {
            log.info("업종 조회 통로 켜짐 — 연동 한 번당 최대 {}곳", props.getMaxPerSync());
        }
    }

    /** 제한시간을 건 요청 팩토리. 기본값은 무한 대기라, 조회처가 멈추면 연동이 같이 멈춘다. */
    private static org.springframework.http.client.ClientHttpRequestFactory factory(int timeoutMs) {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(timeoutMs));
        f.setReadTimeout(Duration.ofMillis(timeoutMs));
        return f;
    }

    /** 켜져 있고 부를 수 있는가. 부르는 쪽이 루프 전에 한 번만 확인하면 된다. */
    public boolean usable() {
        return client != null && extractor != null;
    }

    /**
     * 이 사업자번호의 등록 업종으로 중분류를 정한다 — 못 정하면 {@link Optional#empty()}.
     *
     * <p>빈 값이 돌아오는 경우가 여럿이고 <b>전부 정상</b>이다: 통로가 꺼졌거나, 번호가 없거나,
     * 방어벽에 걸렸거나, 조회처가 모르거나, 답한 업종이 대조표에 없거나(제조·도매·B2B),
     * 코드가 두 중분류로 갈리거나. 어느 쪽이든 다음 칸(LLM)이 받는다.
     */
    public Optional<String> midOf(String businessNumber) {
        return industryOfMerchant(businessNumber)
                .map(mapper::midOfFineName)
                .filter(mid -> !IndustryCategoryMapper.UNCLASSIFIED.equals(mid));
    }

    /**
     * <b>방어벽을 지난 뒤의 등록 업종 이름</b> — 중분류로 옮기기 전 원문이다.
     *
     * <p>{@link #midOf} 와 나눠 둔 이유는 <b>못 붙인 답도 남겨야</b> 하기 때문이다. '아파트
     * 건설업'이라는 답을 받고 중분류가 없다고 버리면, 다음 연동에서 같은 번호를 또 조회한다.
     * 조회에 성공했다는 것과 그 업종이 소비 업종이 아니라는 것은 <b>둘 다 사실</b>이라
     * 둘 다 기록할 값이 있다.
     */
    public Optional<String> industryOfMerchant(String businessNumber) {
        if (!usable()) return Optional.empty();
        String biz = businessNumber == null ? "" : businessNumber.replaceAll("\\D", "");
        if (biz.length() != 10) return Optional.empty();
        if (mapper.isPaymentAgency(biz) || mapper.isMultiBusiness(biz)) return Optional.empty();
        if (!kinds.relaxationAllowed(biz)) return Optional.empty();
        return industryOf(biz);
    }

    /**
     * 조회처가 말하는 업종 이름 — 실패는 전부 빈 값이다.
     *
     * <p>예외를 삼키는 것이 여기서는 맞다. 부르는 쪽에 필요한 답은 "업종을 알아냈는가" 하나뿐이고,
     * 못 알아낸 이유(연결 실패·타임아웃·형식 변경)가 갈라져 봐야 할 일이 같다. 다만 <b>기록은
     * 남긴다</b> — 조용히 안 되면 통로가 죽은 것을 아무도 모른다.
     */
    Optional<String> industryOf(String businessNumber) {
        String hyphenated = businessNumber.substring(0, 3) + '-'
                + businessNumber.substring(3, 5) + '-' + businessNumber.substring(5);
        try {
            String body = client.get()
                    .uri(props.getUrl().replace("{businessNumber}", hyphenated))
                    .retrieve()
                    .body(String.class);
            if (body == null) return Optional.empty();
            Matcher m = extractor.matcher(body);
            if (!m.find() || m.groupCount() < 1) return Optional.empty();
            String name = m.group(1);
            return name == null || name.isBlank() ? Optional.empty() : Optional.of(name.trim());
        } catch (RuntimeException e) {
            log.debug("업종 조회 실패 {} — {}", hyphenated, e.toString());
            return Optional.empty();
        }
    }

    /** 남의 서버를 연달아 두드리지 않게 사이를 둔다. 인터럽트는 삼키지 않고 되살린다. */
    public void pause() {
        if (props.getDelayMs() <= 0) return;
        try {
            Thread.sleep(props.getDelayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int maxPerSync() {
        return props.getMaxPerSync();
    }
}
