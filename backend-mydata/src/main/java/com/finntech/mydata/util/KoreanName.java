package com.finntech.mydata.util;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * 한국 사람 이름 — <b>성씨 1글자 + 이름 2글자, 언제나 세 글자</b>.
 *
 * <p><b>표는 하나다.</b> {@code scripts/identity/} 의 TSV 세 벌을 {@code build_names.py} 가
 * {@code korean-names.json} 으로 굳히고, 파이썬(신원 재부여)과 여기가 그것을 함께 읽는다.
 * 목록을 자바에도 적어 두면 두 벌이 갈라진다 — 업종 표({@code industry-mid.json})와 같은 이유다.
 *
 * <p><b>성씨는 인구수로 가중한다.</b> 예전에는 {@code {"김","김","김","이","이",…}} 처럼 배열에
 * 중복 배치해 분포를 흉내 냈다. 그러면 비율의 근거를 되짚을 수 없고, 성씨를 하나 더할 때마다
 * 다른 것들의 중복 수를 다시 계산해야 한다. 인구수를 그대로 두고 누적합 위에서 이분 탐색한다.
 *
 * <p><b>성별은 주민등록번호 7번째 자리가 정한다</b> — 1·3 남, 2·4 여. 이걸 안 보면
 * 「김철수」가 여성으로, 「이영희」가 남성으로 나온다.
 *
 * <p><b>만들 수 있는 이름을 미리 다 펼쳐 둔다.</b> 뽑고 나서 겹말·금지 조합을 걸러 다시 뽑으면
 * 같은 시드라도 재시도 횟수에 따라 난수 흐름이 달라져 재현성이 흔들린다(마스터 §4 원칙 3).
 * 성별당 1,000가지 남짓이라 펼쳐도 부담이 없다.
 */
public final class KoreanName {

    private static final String RESOURCE = "korean-names.json";

    private final String[] surnames;
    /** 성씨 인구의 누적합. 마지막 값이 전체 인구다. */
    private final long[] cumulative;
    private final String[] maleGiven;
    private final String[] femaleGiven;

    private static final KoreanName INSTANCE = new KoreanName();

    public static KoreanName instance() {
        return INSTANCE;
    }

    @SuppressWarnings("unchecked")
    private KoreanName() {
        Map<String, Object> root;
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            root = new ObjectMapper().readValue(in, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException(RESOURCE + " 를 읽지 못했다 — "
                    + "python3 scripts/identity/build_names.py 로 만든다", e);
        }

        List<String> names = new ArrayList<>();
        List<Long> acc = new ArrayList<>();
        long running = 0;
        for (Map<String, Object> s : (List<Map<String, Object>>) root.get("surnames")) {
            names.add((String) s.get("surname"));
            running += ((Number) s.get("population")).longValue();
            acc.add(running);
        }
        this.surnames = names.toArray(String[]::new);
        this.cumulative = acc.stream().mapToLong(Long::longValue).toArray();

        TreeSet<String> blocked = new TreeSet<>();
        List<String> raw = (List<String>) root.get("blocked");
        if (raw != null) blocked.addAll(raw);

        List<Map<String, Object>> syllables = (List<Map<String, Object>>) root.get("syllables");
        this.maleGiven = expand(syllables, 'M', blocked);
        this.femaleGiven = expand(syllables, 'F', blocked);
    }

    /** 그 성별이 쓸 수 있는 이름 두 글자를 전부 펼친다. 정렬해 두어 순서가 실행마다 같다. */
    private static String[] expand(List<Map<String, Object>> syllables, char gender,
                                   TreeSet<String> blocked) {
        List<String> first = new ArrayList<>(), second = new ArrayList<>();
        for (Map<String, Object> s : syllables) {
            String ch = (String) s.get("syllable");
            char g = ((String) s.get("gender")).charAt(0);
            char pos = ((String) s.get("position")).charAt(0);
            if (g != gender && g != 'N') continue;
            if (pos == '1' || pos == 'B') first.add(ch);
            if (pos == '2' || pos == 'B') second.add(ch);
        }
        TreeSet<String> out = new TreeSet<>();
        for (String a : first) {
            for (String b : second) {
                if (a.equals(b)) continue;              // 겹말 — 「민민」
                String pair = a + b;
                if (!blocked.contains(pair)) out.add(pair);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException(gender + " 성별의 이름을 만들 수 없다 — 표를 확인한다");
        }
        return out.toArray(String[]::new);
    }

    /** 인구수에 비례해 성씨를 뽑는다. 김이 22%, 이가 15% 나온다. */
    public String surname(Random rng) {
        long pick = Math.floorMod(rng.nextLong(), cumulative[cumulative.length - 1]);
        int lo = 0, hi = cumulative.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] <= pick) lo = mid + 1; else hi = mid;
        }
        return surnames[lo];
    }

    /**
     * 이름 두 글자.
     *
     * @param genderDigit 주민등록번호 7번째 자리. 1·3 이면 남성, 아니면 여성으로 본다.
     */
    public String given(Random rng, char genderDigit) {
        String[] pool = (genderDigit == '1' || genderDigit == '3') ? maleGiven : femaleGiven;
        return pool[rng.nextInt(pool.length)];
    }

    /** 성씨 + 이름 = 세 글자. */
    public String full(Random rng, char genderDigit) {
        return surname(rng) + given(rng, genderDigit);
    }

    /** 성별을 따지지 않는 자리(이체 상대 이름 등) — 남녀를 반반으로 고른다. */
    public String full(Random rng) {
        return full(rng, rng.nextBoolean() ? '1' : '2');
    }

    public int surnameCount() { return surnames.length; }
    public int givenCount(char genderDigit) {
        return ((genderDigit == '1' || genderDigit == '3') ? maleGiven : femaleGiven).length;
    }
}
