package com.finntech.engine;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 실적 제외 코드를 <b>승인내역에서 뺄 수 있는 카드혜택 축</b>으로 옮긴다.
 *
 * <p>절감액 계산 1단계가 쓴다 — <i>전달 승인내역 → 실적 제외 빼기 → 전월실적</i>.
 * <b>이 단계를 빼먹으면 실적이 과대 계산된다</b>: 총소비 45만이어도 아파트관리비·공과금·
 * 대중교통이 13만이면 실적은 32만이다.
 *
 * <p><b>왜 코드가 아니라 리소스인가.</b> 이 표는 축 이름투성이고, 원칙 4 는 "카테고리 이름을
 * 코드에 박지 않는다"이다. 카드와 함께 {@code card-catalog.json} 으로 나가고 여기서는 읽기만
 * 한다. {@link IndustryCategoryMapper} 가 {@code industry-mid.json} 을 그렇게 쓰는 것과 같다.
 *
 * <p><b>빈 목록은 "제외 대상이 없다"가 아니라 "승인내역으로 판정할 수 없다"이다.</b>
 * 현금서비스·카드론·수수료는 애초에 가맹점 결제가 아니고, 상품권 구매와 무이자할부는 승인내역에
 * 그 칸이 없다. 이것들을 못 빼면 실적이 <b>과대</b> 계산되는데 — 하한 방향이 아니라 나쁜
 * 방향이다 — 대신할 신호가 없다. 그래서 {@link #unjudgeable}로 <b>몇 개를 못 뺐는지 셀 수는
 * 있게</b> 해 둔다. "빼먹은 것"과 "못 빼는 것"은 다르다.
 */
@Component
public class CardExclusionPolicy {

    private static final String PATH = "card-catalog.json";

    private final Map<String, List<String>> axesByCode;

    @SuppressWarnings("unchecked")
    public CardExclusionPolicy(ObjectMapper objectMapper) {
        try (InputStream is = new ClassPathResource(PATH).getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(is, Map.class);
            Map<String, List<String>> axes = (Map<String, List<String>>) root.get("exclusionAxes");
            this.axesByCode = axes == null ? Map.of() : axes;
        } catch (IOException e) {
            throw new UncheckedIOException("카드 카탈로그를 읽지 못했다: " + PATH, e);
        }
    }

    /**
     * 이 코드들이 실적에서 빼라고 지시하는 축 전부.
     *
     * <p>카드마다 목록이 다르다 — ZONE 은 대중교통을 빼는데 KaPick 은 안 뺀다. 그래서 카드의
     * 제외 목록을 그대로 받아서 축으로 옮긴다. 공통 목록으로 뭉치면 안 된다.
     */
    public Set<String> axesToExclude(List<String> codes) {
        Set<String> out = new LinkedHashSet<>();
        for (String code : codes) {
            out.addAll(axesByCode.getOrDefault(code, List.of()));
        }
        return out;
    }

    /**
     * 이 목록 중 <b>승인내역으로 판정할 수 없는</b> 코드 수.
     *
     * <p>0 이 아니면 우리가 뺀 실적이 실제보다 <b>크다</b>(= 구간을 실제보다 높게 볼 수 있다).
     * 화면에는 안 나가지만, 숫자를 얼마나 믿을지 판단하는 자리에서는 알아야 한다.
     */
    public long unjudgeable(List<String> codes) {
        return codes.stream().filter(c -> axesByCode.getOrDefault(c, List.of()).isEmpty()).count();
    }
}
