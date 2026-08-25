package com.finntech.engine;

import com.finntech.service.IndustryPrompt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>소분류 — 중분류보다 작고 브랜드보다 큰 칸.</b>
 *
 * <p>카카오T 는 브랜드, 교통/자동차 는 중분류인데 그 사이의 '택시' 를 적을 자리가 없었다.
 * 배달의민족과 식비 사이의 '배달' 도 같다.
 *
 * <p>여기서 잠그는 것은 <b>불변식 하나</b>다 — <b>소분류는 정확히 한 중분류에만 속한다.</b>
 * 그것이 성립해야 소분류를 아는 것만으로 중분류가 결정되고, 같은 브랜드가 통로(업종코드·
 * 등록조회·LLM)에 따라 갈리지 않는다. 표가 어긋나면 <b>조용히 틀린다</b> — 화면에 그냥
 * 다른 카테고리로 보일 뿐이라 아무도 눈치채지 못한다.
 */
class SubCategoryTest {

    private final IndustryCategoryMapper mapper = new IndustryCategoryMapper(new ObjectMapper());

    // ── 불변식 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("소분류는 정확히 한 중분류에만 속한다")
    void 한_중분류에만_속한다() {
        for (String sub : mapper.subCategories()) {
            assertThat(IndustryCategoryMapper.isUnknown(mapper.midOfSub(sub)))
                    .as("소분류 '%s' 이 중분류를 못 찾는다", sub).isFalse();
        }
    }

    @Test
    @DisplayName("업종 이름의 소분류가 그 업종의 중분류와 같다")
    void 업종과_소분류가_같은_중분류다() {
        for (String name : mapper.industryNamesByMid().values().stream().flatMap(List::stream).toList()) {
            String sub = mapper.subOfIndustryName(name);
            assertThat(sub).as("업종 '%s' 에 소분류가 없다", name).isNotEmpty();
            assertThat(mapper.midOfSub(sub))
                    .as("업종 '%s' 의 중분류와 소분류 '%s' 의 중분류가 다르다", name, sub)
                    .isEqualTo(mapper.midOfIndustryName(name));
        }
    }

    /** 이름이 겹치면 읽는 사람이 어느 층을 보는지 알 수 없다. */
    @Test
    @DisplayName("소분류 이름은 한 낱말이고 중분류 이름과 겹치지 않는다")
    void 이름이_겹치지_않는다() {
        for (String sub : mapper.subCategories()) {
            assertThat(sub).as("소분류 '%s' 에 둘이 섞였다", sub).doesNotContain("·", "/", ",");
            assertThat(mapper.midCategories()).as("소분류 '%s' 이 중분류 이름과 같다", sub)
                    .doesNotContain(sub);
        }
    }

    // ── 브랜드 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("업종 이름이 못 주는 답을 브랜드가 준다")
    void 브랜드가_답한다() {
        assertThat(mapper.subOfBrand("배달의민족")).isEqualTo("배달");
        assertThat(mapper.midOfSub("배달")).as("배달은 쇼핑이 아니라 식비다").isEqualTo("식비");
        assertThat(mapper.subOfBrand("카카오T")).isEqualTo("택시");
        assertThat(mapper.subOfBrand("넷플릭스")).isEqualTo("구독");
    }

    /**
     * <b>한 지붕 여러 업태</b> — 표가 회사명과 서비스명을 갈라 두어야 성립한다.
     * '카카오' 가 멜론의 표기였을 때 실사용자의 카카오택시 72곳이 전부 멜론이 됐다
     * (brand-forms.json _howNote, 2026-08-08 운영 실측).
     */
    @Test
    @DisplayName("같은 지붕의 다른 서비스는 다른 소분류다")
    void 한_지붕_여러_업태() {
        assertThat(mapper.subOfBrand("쿠팡")).isEqualTo("온라인몰");
        assertThat(mapper.subOfBrand("쿠팡이츠")).isEqualTo("배달");
        assertThat(mapper.subOfBrand("쿠팡플레이")).isEqualTo("구독");
        assertThat(mapper.subOfBrand("이마트")).isNotEqualTo(mapper.subOfBrand("이마트24"));
        assertThat(mapper.subOfBrand("노브랜드")).isNotEqualTo(mapper.subOfBrand("노브랜드버거"));
    }

    /**
     * <b>회사명에는 안 붙인다.</b> 여러 업태를 겸해 하나로 안 정해지는데, 대표 업태를 찍으면
     * 그 브랜드 전체가 한꺼번에 틀린다. 모르는 것을 모른다고 두는 편이 낫다 —
     * 이 코드베이스가 '카테고리없음' 과 '기타' 를 가른 것과 같은 이치다.
     */
    @Test
    @DisplayName("회사명과 결제수단에는 소분류를 안 붙인다")
    void 회사명에는_안_붙인다() {
        for (String company : List.of("카카오", "애플", "구글", "인터파크")) {
            assertThat(mapper.subOfBrand(company)).as("회사명 '%s' 에 소분류가 붙었다", company).isEmpty();
        }
        for (String pay : List.of("카카오페이", "네이버페이", "삼성페이", "토스", "페이코")) {
            assertThat(mapper.subOfBrand(pay)).as("결제수단 '%s' 에 소분류가 붙었다", pay).isEmpty();
        }
    }

    /**
     * <b>전면 재점검(2026-08-25)에서 잡은 자리들</b> — 구조 검사는 전부 통과하는데 <b>뜻이
     * 틀렸던</b> 것들이다. 소분류가 한 중분류에만 속하는지, 전부 배정됐는지는 빌드가 보지만
     * "골프존이 게임장인가"는 사람이 봐야 안다. 그래서 여기 못박는다.
     */
    @Test
    @DisplayName("재점검에서 고친 자리가 되돌아가지 않는다")
    void 재점검에서_고친_자리() {
        // 주차장 운영은 통행료가 아니다 — 국세청 표에 '주차장 운영업' 이 없어 브랜드로만 산다.
        assertThat(mapper.subOfBrand("나이스파크")).isEqualTo("주차");
        assertThat(mapper.subOfBrand("한국도로공사")).isEqualTo("통행료");
        // 따릉이는 킥보드가 아니라 자전거다 — 이름이 내용을 배신하지 않게 '공유이동' 으로 묶는다.
        assertThat(mapper.subOfBrand("따릉이")).isEqualTo("공유이동");
        // 게임 플랫폼은 앱마켓과 성격이 다르고, 게임사는 표에 아예 없었다.
        assertThat(mapper.subOfBrand("넥슨")).isEqualTo("게임");
        assertThat(mapper.subOfBrand("스팀")).isEqualTo("게임");
        assertThat(mapper.subOfBrand("구글플레이")).isEqualTo("앱마켓");
        // 예약 플랫폼은 '생활 숙박시설' 이라는 업종과 다른 것이다.
        assertThat(mapper.subOfBrand("야놀자")).isEqualTo("숙박예약");
        // 이름만 보고 엉뚱한 칸에 들어가 있던 것들.
        assertThat(mapper.subOfBrand("골프존")).as("스크린골프는 게임장이 아니다").isEqualTo("골프");
        assertThat(mapper.subOfBrand("낙원악기상가")).as("악기 상가는 전시가 아니다").isEqualTo("악기");
        assertThat(mapper.subOfBrand("이리온")).as("동물병원은 용품점이 아니다").isEqualTo("수의");
        assertThat(mapper.subOfBrand("모나미스토어")).as("문구는 패션몰이 아니다").isEqualTo("문구");
        assertThat(mapper.subOfBrand("오늘의집")).as("인테리어는 패션몰이 아니다").isEqualTo("인테리어");
        assertThat(mapper.subOfBrand("스포츠몬스터")).as("체험시설은 용품점이 아니다").isEqualTo("스포츠시설");
        // 소분류 이름과 담긴 업종이 어긋나 있었다 — 퀵서비스배달원이 '택배' 에 있었다.
        assertThat(mapper.subOfIndustryName("퀵서비스배달원")).isEqualTo("퀵서비스");
    }

    /** 업종도 브랜드도 없는 소분류는 <b>영원히 빈 칸</b>이다 — 표에 있을 이유가 없다. */
    @Test
    @DisplayName("죽은 소분류가 없다 — 업종이든 브랜드든 하나는 닿는다")
    void 죽은_칸이_없다() {
        java.util.Set<String> reached = new java.util.TreeSet<>();
        for (String name : mapper.industryNamesByMid().values().stream().flatMap(List::stream).toList()) {
            reached.add(mapper.subOfIndustryName(name));
        }
        for (String brand : mapper.brandsWithSub()) reached.add(mapper.subOfBrand(brand));
        assertThat(mapper.subCategories()).allSatisfy(sub ->
                assertThat(reached).as("소분류 '%s' 에 닿는 업종도 브랜드도 없다", sub).contains(sub));
    }

    @Test
    @DisplayName("모르는 것에는 빈 값을 준다 — 찍지 않는다")
    void 모르면_비운다() {
        assertThat(mapper.subOfBrand("듣도보도못한브랜드")).isEmpty();
        assertThat(mapper.subOfIndustryName("우주선 정비업")).isEmpty();
        assertThat(mapper.subOfBrand(null)).isEmpty();
        assertThat(IndustryCategoryMapper.isUnknown(mapper.midOfSub("없는소분류"))).isTrue();
        assertThat(IndustryCategoryMapper.isUnknown(mapper.midOfSub(null))).isTrue();
    }

    // ── 모호 업종 ────────────────────────────────────────────────────────────

    /**
     * <b>판매 방식만 말하고 무엇을 파는지 말하지 않는 업종</b>은 중분류를 못 준다.
     * 전자상거래 소매업 하나에 배달의민족·넥슨·야놀자가 함께 등록돼 있고,
     * 온라인이라고 단정할 수도 없다 — 오프라인 매장을 함께 운영한다.
     */
    @Test
    @DisplayName("모호한 업종은 중분류를 안 준다")
    void 모호한_업종은_안_준다() {
        for (String vague : List.of("전자상거래 소매업", "전자상거래 소매 중개업", "기타 통신 판매업",
                "해외직구대행업", "SNS마켓", "그 외 기타 무점포 소매업", "방문 판매업", "계약배달 판매업",
                "그 외 기타 분류 안된 상품 전문 소매업", "그 외 기타 분류 안된 가정용품 소매업",
                "그 외 기타 달리 분류되지 않은 개인 서비스업", "기타자영업")) {
            assertThat(IndustryCategoryMapper.isUnknown(mapper.midOfIndustryName(vague)))
                    .as("모호한 업종 '%s' 이 아직 중분류를 준다", vague).isTrue();
        }
        assertThat(IndustryCategoryMapper.isUnknown(mapper.midOf("525101")))
                .as("업종코드 525101 이 아직 중분류를 준다").isTrue();
    }

    /** 목록에 남으면 모델이 <b>모를 때 집어드는 탈출구</b>가 된다 — 물어볼수록 쇼핑이 는다. */
    @Test
    @DisplayName("모호한 업종은 LLM 목록에도 안 나간다")
    void 모델에게_안_보여_준다() {
        assertThat(IndustryPrompt.industryList(mapper))
                .doesNotContain("전자상거래").doesNotContain("SNS마켓").doesNotContain("무점포");
    }

    /**
     * <b>소비 중분류는 틀리고 카드 혜택축은 맞다.</b> 카드사도 온라인 할인을 이 업종코드로
     * 판정하므로 우리 축이 카드사 기준과 같아진다. 게다가 525101~525105 가 '온라인쇼핑' 축의
     * <b>전부</b>라, 빼면 온라인 할인 카드 추천이 통째로 사라진다.
     */
    @Test
    @DisplayName("카드 혜택축은 그대로 살아 있다 — 이번에 안 건드린다")
    void 카드축은_그대로다() {
        for (String code : List.of("525101", "525102", "525103", "525104", "525105")) {
            assertThat(mapper.cardAxisOf(code))
                    .as("업종코드 %s 의 온라인쇼핑 축이 사라졌다", code).isEqualTo("온라인쇼핑");
        }
    }
}
