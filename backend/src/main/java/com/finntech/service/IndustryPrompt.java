package com.finntech.service;

import com.finntech.engine.IndustryCategoryMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>업종을 묻는 프롬프트 — 유료·무료가 같은 것을 쓴다.</b>
 *
 * <h2>왜 한 곳에 두는가</h2>
 *
 * <p>두 통로가 각자 프롬프트를 들고 있었고, <b>둘 다 같은 잘못</b>을 하고 있었다. 한쪽만
 * 고치면 다른 쪽에 그대로 남는다.
 *
 * <h2>고친 것 ① — 중분류를 보여주지 않는다</h2>
 *
 * <p>예전에는 업종 목록을 {@code [카페/간식] 커피 전문점 · 제과점업} 처럼 <b>중분류로 묶어</b>
 * 보여줬다. 그러면 모델이 업종이 아니라 <b>우리 축</b>을 보고 답한다 — 실제로 상위 모델의
 * 오답 대부분이 거기서 나왔다(스타필드 → {@code 대형 마트}, 닭발집 → {@code 한식 육류 요리}).
 * 마스터 §4 원칙 1 은 "축 배정은 우리 표가 한다"인데 프롬프트가 그 축을 모델에게 알려 주고
 * 있었던 것이다.
 *
 * <p>이제 목록은 <b>국세청 세세분류 이름만</b> 쉼표로 늘어놓는다. 모델은 "이 가게가 무엇을
 * 파는가"라는 사실만 답하고, 그 이름을 중분류로 옮기는 일은 {@link IndustryCategoryMapper}
 * 가 한다 — 프롬프트가 아니라 우리 알고리즘이.
 *
 * <h2>고친 것 ② — 한 번에 한 가맹점만 묻는다</h2>
 *
 * <p>예전에는 40곳을 번호로 묶어 묻고 JSON 을 받았다. 묶으면 <b>모델이 앞 답에 끌려간다</b>
 * (앞이 카페면 뒤도 카페로 몰린다). 답도 JSON 파싱이 필요해 형식이 깨지면 묶음 전체를 잃었다.
 * 한 곳씩 물으면 답은 <b>단어 하나</b>이고, 하나가 실패해도 그 하나만 잃는다.
 *
 * <h2>고친 것 ③ — 가맹점명을 앞뒤로 두 번 말한다</h2>
 *
 * <p>목록이 385종·<b>5,136자</b>라 프롬프트의 거의 전부다. 가맹점명을 앞에만 두면 그 긴 목록에
 * 묻혀 모델이 무엇을 묻는지 놓친다. 목록 뒤에 한 번 더 말해 주고, <b>두 자리 모두 굵게</b>
 * 감싼다 — 평문으로 두면 5천 자 사이에서 눈에 안 띈다.
 *
 * <h2>그대로 옮긴 것 — 실측으로 얻은 규칙 여섯 줄</h2>
 *
 * <p>바꾼 것은 <b>구조</b>이지 규칙이 아니다. 아래는 지난 탐구의 결과라 한 줄도 버리지 않았다:
 *
 * <ul>
 *   <li><b>"가장 가까운 것을 고르라"</b> — 처음엔 "명백한 것만, 틀리느니 답하지 마라"였는데
 *       377개 목록과 함께 주니 모델이 과하게 보수적이 되어 커버리지가 <b>21종</b>으로 떨어졌다.
 *       이 한 줄로 <b>55종</b>이 됐다(2026-08-05 실데이터 86종 대조).</li>
 *   <li><b>해외 가맹점도 고르라</b> — 영문 상호를 통째로 버리던 것을 막는다.</li>
 *   <li><b>결제대행사는 모름</b> · <b>브랜드 자체 결제는 그 브랜드</b> — '컬리페이'를 PG 로
 *       오인해 버리던 것을 가른다.</li>
 *   <li><b>뜻 없는 상호·사람 이름·숫자뿐인 것은 모름</b></li>
 *   <li><b>글자 그대로 쓰라</b> — 표기를 바꾸면 대조표를 못 넘는다.</li>
 * </ul>
 */
public final class IndustryPrompt {

    private IndustryPrompt() {}

    /**
     * 업종 이름만 쉼표로 늘어놓는다 — <b>중분류는 넣지 않는다.</b>
     *
     * <p>순서를 고정한다({@code industryNamesByMid} 가 이미 정렬된 것을 그대로 이어 붙인다).
     * 같은 입력에 같은 프롬프트가 나와야 같은 답이 나온다(§4 원칙 3 재현성).
     */
    public static String industryList(IndustryCategoryMapper mapper) {
        List<String> names = new ArrayList<>();
        mapper.industryNamesByMid().values().forEach(names::addAll);
        return String.join(", ", names);
    }

    /**
     * 가맹점 하나를 묻는 프롬프트.
     *
     * @param merchantName 카드 명세서에 찍힌 상호 그대로
     * @param industryList {@link #industryList}(부르는 쪽이 한 번 만들어 돌려 쓴다 — 5,600자다)
     */
    public static String of(String merchantName, String industryList) {
        return of(merchantName, null, industryList);
    }

    /**
     * 브랜드를 알면 함께 준다.
     *
     * <p>실패한 답이 <b>전부</b> {@code (주)…} 로 시작했다 — {@code (주)마포애경타운-새틀라이트문구外}
     * 같은 문자열은 모델에게 잡음이다. 법인격·지점명을 떼어 낸 이름과 브랜드를 함께 주면
     * 모델이 볼 것이 줄어든다. <b>원문도 같이 준다</b> — 정제가 잘못 깎았을 때 원문이 그것을
     * 되돌릴 근거가 된다.
     */
    public static String of(String merchantName, String brand, String industryList) {
        // **이름표만 붙이지 않는다.** 예전에는 `브랜드 : 올리브영` 한 줄이었는데, 그게
        // 무엇이고 어떻게 쓰라는 말이 없어 모델이 그냥 지나칠 수 있었다. 문장으로 말한다.
        String hint = (brand == null || brand.isBlank() || brand.equals(merchantName))
                ? "" : "\n\n이 가맹점의 브랜드는 **" + brand.trim() + "** 입니다. 업종을 고를 때 참고하세요.";
        return """
                아래는 한국 카드 명세서에 찍힌 가맹점명입니다. 이 가맹점이 어느 업종인지 고르세요.

                가맹점명 : **%s**%s

                업종 목록입니다. 답에는 **목록에 포함된 업종 이름만** 쓰세요. 답변은 업종 이름 \
                하나만 단답으로 회신해야 합니다. 다른 설명을 아예 하면 안되고, 문장이 되어서도 \
                절대 안 됩니다. 오직 **업종 이름 하나만** 단답 단어로 답변해야 합니다.

                - 무엇을 파는지 알겠다면 **목록에서 가장 가까운 업종**을 고르세요. 딱 맞는 것이
                  없어도 가장 가까운 것을 고르면 됩니다.
                - **해외 가맹점도 마찬가지입니다.** 영문·로마자 상호라도 무엇을 파는 곳인지
                  알겠다면 고르세요(예: 공항 면세점, 해외 호텔, 해외 항공사).
                - 결제대행사 상호(토스페이먼츠, 나이스페이먼츠, KG이니시스, 네이버페이,
                  카카오페이 등)는 **여러 가게의 결제를 대신 처리하는 회사**라 무엇을 샀는지
                  알 수 없습니다. 그럴 때는 모름 이라고만 쓰세요.
                - 다만 **한 브랜드의 자체 결제 수단**은 그 브랜드로 판단하세요 — 이름에 '페이'가
                  붙었다고 빼면 안 됩니다. '컬리페이'는 마켓컬리에서 산 것이고,
                  '무신사페이먼츠'는 무신사에서 산 것입니다.
                - 뜻을 알 수 없는 상호, 사람 이름만 있는 것, 숫자뿐인 것도 모름 입니다.
                - 목록에 있는 이름을 **글자 그대로** 쓰세요.

                업종 목록 :
                %s

                가맹점명을 다시 알려드리겠습니다. 가맹점명은 **%s** 입니다. 위의 업종 목록 중에서, \
                이 가맹점이 어떤 업종인지를 골라서 **단답 단어**로 답변하시기 바랍니다.
                """.formatted(merchantName, hint, industryList, merchantName);
    }

    /**
     * <b>후보를 추린다</b> — 2단계가 쓸 짧은 목록.
     *
     * <p>385종을 매번 통째로 스캔시키면 모델이 놓친다. 상호와 <b>낱말이 겹치는</b> 업종을
     * 앞에 세워 30종쯤으로 줄인다. 판단은 여전히 모델이 하고, 여기서 하는 것은 <b>추림</b>이다.
     *
     * <p><b>추리다 정답을 흘릴 수 있다.</b> 그래서 2단계 혼자 쓰지 않는다 — 1단계(전체 스캔)와
     * 견주고 3단계가 고른다. 이 위험을 아는 채로 두는 것이 이 설계의 요점이다.
     *
     * <p>겹치는 것이 모자라면 <b>앞에서부터 채운다.</b> 빈 목록을 주면 모델이 아무 말이나 한다.
     */
    public static List<String> narrow(String merchantName, IndustryCategoryMapper mapper, int size) {
        List<String> all = new ArrayList<>();
        mapper.industryNamesByMid().values().forEach(all::addAll);
        String n = merchantName == null ? "" : merchantName.replaceAll("\\s+", "");

        // 상호에서 두 글자짜리 조각을 뽑아 업종 이름과 겹치는지 본다. 한 글자는 아무 데나
        // 걸리고(‘사’·‘점’), 세 글자는 거의 안 걸린다.
        java.util.Set<String> grams = new java.util.LinkedHashSet<>();
        for (int i = 0; i + 2 <= n.length(); i++) grams.add(n.substring(i, i + 2));

        java.util.Map<String, Integer> score = new java.util.LinkedHashMap<>();
        for (String name : all) {
            String flat = name.replaceAll("\\s+", "");
            int hit = 0;
            for (String g : grams) if (flat.contains(g)) hit++;
            if (hit > 0) score.put(name, hit);
        }
        List<String> picked = score.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .map(java.util.Map.Entry::getKey)
                .limit(size)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (String name : all) {                 // 모자라면 앞에서부터 채운다
            if (picked.size() >= size) break;
            if (!picked.contains(name)) picked.add(name);
        }
        return picked;
    }

    /**
     * <b>3단계 — 둘 중 하나를 고르게 한다.</b>
     *
     * <p>1단계(전체 목록)와 2단계(추린 목록)가 다른 답을 냈을 때 부른다. <b>가맹점명만</b>
     * 준다 — 브랜드도 목록도 주지 않는다. 앞 두 단계가 이미 그것을 보고 답했으므로, 여기서는
     * 그 둘만 놓고 새 눈으로 견주게 한다.
     *
     * <p>이 저장소에 이미 같은 형태가 있다({@code MerchantClassifierService.tieBreak} —
     * 무료와 유료가 갈렸을 때 하나를 고르게 하는 것). 검증된 모양을 한 통로 안으로 들인다.
     */
    public static String tieBreak(String merchantName, String a, String b) {
        return """
                한국 카드 명세서에 찍힌 가맹점명 하나와, 그 가맹점의 업종 후보 둘이 있습니다.
                둘 중 <b>더 가까운 것 하나</b>를 고르세요.

                가맹점명 : %s

                후보 1 : %s
                후보 2 : %s

                - 두 후보 중 하나를 **글자 그대로** 쓰세요. 설명·기호 없이 한 줄로만.
                - 새로운 업종을 지어내지 마세요. 반드시 위 둘 중 하나여야 합니다.
                """.formatted(merchantName, a, b).replace("<b>", "**").replace("</b>", "**");
    }

    /**
     * 답을 업종 이름으로 다듬는다 — <b>목록에 실제로 있는 이름만</b> 통과시킨다.
     *
     * <p>모델이 군말을 붙이거나 목록에 없는 말을 지어내는 일이 있다. 그대로 받으면 어느
     * 화면에도 안 잡히는 값이 원장에 생긴다. 여기서 걸러 낸다 — 판단이 아니라 <b>대조</b>다.
     *
     * @return 목록에 있는 업종 이름, 못 찾으면 {@code null}
     */
    public static String pickIndustry(String answer, IndustryCategoryMapper mapper) {
        if (answer == null) return null;
        String a = answer.replaceAll("[\r\n]+", " ")
                .replaceAll("[\"'`*\\[\\]]", "")
                .trim();
        if (a.isEmpty()) return null;

        List<String> names = new ArrayList<>();
        mapper.industryNamesByMid().values().forEach(names::addAll);

        for (String n : names) {                       // 그대로 답한 경우
            if (a.equalsIgnoreCase(n)) return n;
        }
        // 앞뒤에 군말이 붙은 경우 — **가장 긴 것**을 고른다. 짧은 이름이 긴 이름 안에
        // 들어 있는 일이 흔해서(예: '슈퍼마켓' ⊂ '기타 대형 종합 소매업'), 짧은 쪽을 먼저
        // 집으면 엉뚱한 업종이 된다.
        String best = null;
        for (String n : names) {
            if (!a.contains(n)) continue;
            if (best == null || n.length() > best.length()) best = n;
        }
        return best;
    }
}
