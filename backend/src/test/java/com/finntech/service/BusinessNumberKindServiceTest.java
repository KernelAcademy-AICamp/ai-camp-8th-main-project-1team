package com.finntech.service;

import com.finntech.domain.BusinessNumberKind;
import com.finntech.repository.BusinessNumberKindRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 사업자번호가 한 사업인가 여러 사업인가 — <b>관측으로 판정하는 규칙</b>(V16).
 *
 * <p>여기서 지키는 것은 둘이다.
 * <ul>
 *   <li><b>오염을 막는다</b> — 백화점처럼 갈리는 번호는 완화가 닿으면 안 된다</li>
 *   <li><b>택시를 지킨다</b> — 상호가 수천 종이어도 한두 번의 실수로 완화가 무너지면 안 된다</li>
 * </ul>
 * 둘은 반대 방향으로 당기므로, 어느 한쪽만 보면 다른 쪽이 깨진다.
 */
class BusinessNumberKindServiceTest {

    private final Map<String, BusinessNumberKind> table = new HashMap<>();
    private BusinessNumberKindService service;
    private final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 12, 0);

    @BeforeEach
    void setUp() {
        table.clear();
        BusinessNumberKindRepository repo = mock(BusinessNumberKindRepository.class);
        when(repo.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(table.get(inv.<String>getArgument(0))));
        when(repo.save(any(BusinessNumberKind.class))).thenAnswer(inv -> {
            BusinessNumberKind k = inv.getArgument(0);
            table.put(k.getBusinessNumber(), k);
            return k;
        });
        service = new BusinessNumberKindService(repo, 5, 2, 0.10);
    }

    private static Map<String, String> names(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    @Test
    @DisplayName("상호가 하나뿐이면 판정하지 않는다 — 완화해도 오염될 대상이 없다")
    void 상호가_하나면_판정_대상이_아니다() {
        service.observe("0000000011", names("어느 가게", "식비"), Map.of(), NOW);

        assertThat(table).as("행을 만들지 않는다").isEmpty();
        assertThat(service.relaxationAllowed("0000000011"))
                .as("표에 없으면 완화를 허용한다 — 사전 대부분이 이 경로로 붙는다").isTrue();
    }

    @Test
    @DisplayName("중분류가 갈리면 곧바로 복합 — 백화점은 SINGLE 을 거치지 않는다")
    void 갈리면_복합이다() {
        service.observe("0000000022",
                names("롯데백)러쉬", "미용", "롯데백)무인양품", "생활"), Map.of(), NOW);

        assertThat(table.get("0000000022").isMulti()).isTrue();
        assertThat(service.relaxationAllowed("0000000022"))
                .as("완화가 닿으면 입점 브랜드가 한 분류로 오염된다").isFalse();
    }

    @Test
    @DisplayName("충분히 봤고 전부 같으면 단일 — 그 전까지는 보류다")
    void 충분히_봐야_굳는다() {
        // 4종은 굳힘 기준(5)에 못 미친다 → 아직 모른다 → 보류.
        service.observe("0000000033", names(
                "카카오택시-A", "교통/자동차", "카카오택시-B", "교통/자동차",
                "카카오택시-C", "교통/자동차", "카카오택시-D", "교통/자동차"), Map.of(), NOW);
        assertThat(service.relaxationAllowed("0000000033"))
                .as("모를 때는 보류가 기본 — 애매하면 복합 쪽으로 기운다").isFalse();

        // 5종이 되면 굳는다.
        service.observe("0000000033", names(
                "카카오택시-A", "교통/자동차", "카카오택시-B", "교통/자동차",
                "카카오택시-C", "교통/자동차", "카카오택시-D", "교통/자동차",
                "카카오택시-E", "교통/자동차"), Map.of(), NOW);
        assertThat(table.get("0000000033").isSingle()).isTrue();
        assertThat(service.relaxationAllowed("0000000033")).isTrue();
    }

    @Test
    @DisplayName("택시는 실수 한두 번으로 안 무너진다 — 뒤집는 문턱이 관측량에 비례한다")
    void 실수가_택시를_무너뜨리지_못한다() {
        // 상호 40종이 전부 교통으로 모여 굳었다.
        Map<String, String> observed = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) observed.put("카카오택시-" + i, "교통/자동차");
        service.observe("0000000044", observed, Map.of(), NOW);
        assertThat(table.get("0000000044").isSingle()).isTrue();

        // 누군가 두 대를 실수로 '식비'라고 확정했다. 40종의 10% 는 4종이라 아직 모자란다.
        Map<String, String> withMistakes = new LinkedHashMap<>(observed);
        withMistakes.put("카카오택시-0", "식비");
        withMistakes.put("카카오택시-1", "식비");
        service.observe("0000000044", withMistakes,
                names("카카오택시-0", "식비", "카카오택시-1", "식비"), NOW);

        assertThat(table.get("0000000044").isSingle())
                .as("두 건의 실수로 수만 건의 택시 결제가 흩어지면 안 된다").isTrue();
        assertThat(service.relaxationAllowed("0000000044")).isTrue();

        // 넷이 되면 10% 를 채운다 — 그때는 실수가 아니라 그 번호가 원래 갈린 것으로 본다.
        withMistakes.put("카카오택시-2", "식비");
        withMistakes.put("카카오택시-3", "식비");
        service.observe("0000000044", withMistakes, names(
                "카카오택시-0", "식비", "카카오택시-1", "식비",
                "카카오택시-2", "식비", "카카오택시-3", "식비"), NOW);

        assertThat(table.get("0000000044").isMulti()).isTrue();
    }

    @Test
    @DisplayName("추정끼리 갈렸다고 굳은 판정을 뒤집지 않는다 — 모델은 그 무게를 못 진다")
    void 추정은_뒤집지_못한다() {
        Map<String, String> observed = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) observed.put("택시-" + i, "교통/자동차");
        service.observe("0000000055", observed, Map.of(), NOW);

        // 분류가 갈려 보이지만 **사람이 확정한 것은 하나도 없다**.
        Map<String, String> drifted = new LinkedHashMap<>(observed);
        for (int i = 0; i < 10; i++) drifted.put("택시-" + i, "식비");
        service.observe("0000000055", drifted, Map.of(), NOW);

        assertThat(table.get("0000000055").isSingle())
                .as("확정이 없으면 뒤집지 않는다").isTrue();
    }

    @Test
    @DisplayName("문턱은 관측한 상호의 10% — 최소 2종")
    void 문턱은_비율이다() {
        assertThat(service.overturnThreshold(5)).isEqualTo(2);
        assertThat(service.overturnThreshold(20)).isEqualTo(2);
        assertThat(service.overturnThreshold(40)).isEqualTo(4);
        assertThat(service.overturnThreshold(3000)).isEqualTo(300);
    }
}
