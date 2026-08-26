package com.finntech.service;

import com.finntech.engine.IndustryCategoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>소비내역에 무엇을 적는가</b> — 표시명 규칙을 못박는다.
 *
 * <h2>지어내지 않는다</h2>
 *
 * <p>표시명은 <b>언제나 원문의 부분집합</b>이다. 우리가 하는 일은 실제 결제처를 알아내는 것이
 * 아니라 <b>확실히 버려도 되는 것만 버리는 것</b>이라, 새 사실을 만들지 않으므로 틀릴 수가 없다.
 * 모델에게 이름을 짓게 하거나 PG 이름을 브랜드 표에 올리면 <b>지어낸 상호가 사실처럼</b> 보인다.
 *
 * <h2>여기 적힌 사업자번호</h2>
 *
 * <p>PG 번호는 저장소에 이미 있는 <b>공개 목록</b>이고, PG 경계를 시험하려면 그 번호여야 한다.
 * 그 밖은 {@code 0} 으로 시작하는 자리표라 실재하는 사업자와 겹치지 않는다.
 */
class MerchantDisplayNameTest {

    private MerchantDisplayName names;

    @BeforeEach
    void setUp() {
        names = new MerchantDisplayName(new IndustryCategoryMapper(new tools.jackson.databind.ObjectMapper()));
    }

    private static final String TOSS = "4118601799";     // 토스페이먼츠
    private static final String NAVER = "5248601528";    // 네이버파이낸셜
    private static final String KCP = "1138521083";      // NHNKCP
    private static final String NOT_PG = "0000000091";

    /** 브랜드가 있으면 브랜드가 먼저다 — 사람이 검수한 값이라 가장 믿을 만하다. */
    @Test
    @DisplayName("확정 브랜드가 표시명이 된다")
    void 브랜드가_먼저다() {
        var shown = names.of("토스페이_무신사", TOSS, "무신사");

        assertThat(shown.display()).isEqualTo("무신사");
        assertThat(shown.source()).isEqualTo(MerchantDisplayName.Source.BRAND);
        assertThat(shown.viaAgency()).as("경유는 번호가 알려 준 사실이라 브랜드가 있어도 남는다")
                .isEqualTo("토스페이먼츠");
    }

    /**
     * <b>PG 를 걷어내고 남은 것이 가맹점이다.</b> 남는 글자는 전부 원문에 있던 것이라
     * 지어낸 것이 아니다.
     */
    @Test
    @DisplayName("PG 를 걷어내면 원문에 있던 상호가 남는다")
    void 걷어내면_상호가_남는다() {
        assertThat(names.of("KCP - Apple", KCP, null).display()).isEqualTo("Apple");
        assertThat(names.of("인터넷상거래-(주)와이즐리컴퍼니", TOSS, null).display())
                .isEqualTo("와이즐리컴퍼니");
        assertThat(names.of("구글_네이버페이", NAVER, null).display()).isEqualTo("구글");

        var shown = names.of("정기결제_K-주식회사 피클플러스", "1148605588", null);
        assertThat(shown.display()).isEqualTo("피클플러스");
        assertThat(shown.source()).isEqualTo(MerchantDisplayName.Source.RESIDUE);
    }

    /**
     * <b>아무것도 안 남으면 지어내지 않는다.</b> 카드사가 준 정보가 "간편결제로 무언가를
     * 샀다" 뿐인 결제다 — 실사용자 결제에서 190건이 그렇다(2026-08-26 실측).
     */
    @Test
    @DisplayName("걷어내니 남는 게 없으면 결제 경로만 짧게 적는다")
    void 남는_게_없으면_경로만_적는다() {
        var shown = names.of("토스페이_일반-(주)비바리퍼블리카", TOSS, null);

        assertThat(shown.source()).isEqualTo(MerchantDisplayName.Source.AGENCY_ONLY);
        assertThat(shown.display()).as("긴 원문을 그대로 두면 결제수단이 가게처럼 보인다")
                .isEqualTo("토스페이먼츠");
        assertThat(names.of("NICE_통신판매", "2208115770", null).display())
                .as("내부 주석 '(제2번호)' 가 화면에 새면 안 된다").isEqualTo("KIS정보통신");
    }

    /**
     * <b>잘려 들어온 PG 도 PG 다.</b> 명세서는 상호 칸을 잘라 보내는 일이 있어
     * {@code (주)비바리퍼블리} 처럼 끝이 빠진 채로 온다.
     */
    @Test
    @DisplayName("잘린 결제사 이름이 상호로 남지 않는다")
    void 잘린_결제사도_결제사다() {
        assertThat(names.of("토스페이_일반-(주)비바리퍼블리카-(주)비바리퍼블리", TOSS, null).source())
                .isEqualTo(MerchantDisplayName.Source.AGENCY_ONLY);
    }

    /**
     * <b>짧은 낱말을 아무 데서나 지우면 안 된다.</b> 업태어 {@code 구} 로 부분 삭제를 했더니
     * {@code 구글} 이 {@code 글} 이 되어 사라졌다(2026-08-26 실측). 그리고 회사명 {@code 카카오}
     * 가 결제수단 {@code 카카오페이} 에 먹혀 18건이 통째로 '카카오페이'가 됐다.
     */
    @Test
    @DisplayName("짧은 이름이 긴 결제수단에 먹히지 않는다")
    void 짧은_이름이_안_먹힌다() {
        var shown = names.of("주식회사 카카오", "5278800686", null);   // 카카오페이 번호
        assertThat(shown.display()).isEqualTo("카카오");
        assertThat(shown.source()).isEqualTo(MerchantDisplayName.Source.RESIDUE);
    }

    /** 같은 회사가 결제 조직까지 달고 오면 앞의 것 하나만 적는다. */
    @Test
    @DisplayName("같은 이름이 여러 번 나오면 한 번만 적는다")
    void 겹치는_이름은_한_번만() {
        assertThat(names.of("무신사-주식회사 무신사페이먼츠-주식회사 무신사페이", KCP, null).display())
                .isEqualTo("무신사");
        assertThat(names.of("쿠팡-쿠팡", KCP, null).display()).isEqualTo("쿠팡");
    }

    /** PG 가 안 섞인 보통의 상호는 <b>손대지 않는다</b>. */
    @Test
    @DisplayName("PG 가 안 섞이면 원문 그대로다")
    void 보통_상호는_그대로다() {
        var shown = names.of("포항공과대학교복지회", NOT_PG, null);

        assertThat(shown.display()).isEqualTo("포항공과대학교복지회");
        assertThat(shown.source()).isEqualTo(MerchantDisplayName.Source.RAW);
        assertThat(shown.viaAgency()).isNull();
    }

    /** 표시명은 <b>원문에 있던 글자만</b> 쓴다 — 이 시험이 "지어내지 않는다"를 잠근다. */
    @Test
    @DisplayName("표시명은 언제나 원문의 부분집합이다")
    void 지어내지_않는다() {
        String[][] cases = {
                {"KCP - Apple", KCP}, {"구글_네이버페이", NAVER},
                {"인터넷상거래-(주)와이즐리컴퍼니", TOSS}, {"주식회사 카카오", "5278800686"},
                {"토스페이먼츠 - 구글클라우드", TOSS}, {"포항공과대학교복지회", NOT_PG},
        };
        for (String[] c : cases) {
            var shown = names.of(c[0], c[1], null);
            if (shown.source() == MerchantDisplayName.Source.AGENCY_ONLY) continue;
            String flat = c[0].replaceAll("\\s", "");
            for (String token : shown.display().split("\\s+")) {
                assertThat(flat)
                        .as("표시명 조각 '%s' 가 원문 '%s' 에 없다 — 지어낸 것이다", token, c[0])
                        .contains(token);
            }
        }
    }

    /**
     * <b>지점명과 운영사를 접는다.</b> 목록에서 어느 지점인지는 필요 없고, 상호 앞에 붙는
     * 법인은 <i>"어디서 썼나"</i>를 말해 주지 않는다. 원문은 눌러서 본다.
     */
    @Test
    @DisplayName("지점명과 앞의 운영사를 접는다")
    void 핵심만_남긴다() {
        assertThat(names.of("세븐틴코인노래연습장 성신여대역점", NOT_PG, null).display())
                .isEqualTo("세븐틴코인노래연습장");
        assertThat(names.of("미니말레 커피뢰스터 과천 지식정보타운점", NOT_PG, null).display())
                .isEqualTo("미니말레 커피뢰스터 과천");
        assertThat(names.of("에이치디씨현대산업개발(주)고척아이파크쇼핑센터", NOT_PG, null).display())
                .as("돈을 쓴 곳은 뒤다 — 앞은 법인이다").isEqualTo("고척아이파크쇼핑센터");
    }

    /**
     * <b>토막이 하나뿐이면 접지 않는다.</b> 그것이 이름 전체라 접으면 통째로 사라진다 —
     * {@code 친절한정육점} 은 지점이 아니라 정육점이다.
     */
    @Test
    @DisplayName("이름이 '점'으로 끝나도 그것뿐이면 남긴다")
    void 하나뿐이면_안_접는다() {
        assertThat(names.of("친절한정육점", NOT_PG, null).display()).isEqualTo("친절한정육점");
        assertThat(names.of("쿨링쿨링아이스크림할인점남현점", NOT_PG, null).display())
                .isEqualTo("쿨링쿨링아이스크림할인점남현점");
    }

    /** <b>괄호 안은 부연이다.</b> 본문이 있으면 접는다 — 같은 이름을 두 언어로 적은 꼴이 흔하다. */
    @Test
    @DisplayName("괄호 안 병기를 접는다")
    void 괄호는_부연이다() {
        assertThat(names.of("핑크고릴라커피(PINK GORILLA COFFEE)", NOT_PG, null).display())
                .isEqualTo("핑크고릴라커피");
        assertThat(names.of("주식회사 우리들곳간(해피베네핏 성수점)", NOT_PG, null).display())
                .isEqualTo("우리들곳간");
        assertThat(names.of("주식회사 와그(WAUG)", "5278800686", null).display()).isEqualTo("와그");
    }

    /** 괄호밖에 없으면 접을 본문이 없다 — 그때는 괄호 안이 이름이다. */
    @Test
    @DisplayName("본문이 없으면 괄호 안을 쓴다")
    void 본문이_없으면_괄호를_쓴다() {
        assertThat(names.of("(청춘닭발)", NOT_PG, null).display()).isEqualTo("청춘닭발");
    }
}
