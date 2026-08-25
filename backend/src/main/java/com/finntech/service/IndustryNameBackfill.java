package com.finntech.service;

import com.finntech.domain.MerchantCategory;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.freechannel.Lane;
import com.finntech.repository.MerchantCategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>업종 이름을 못 얻어 소분류가 빈 행을 되메운다</b>(V43).
 *
 * <h2>왜 남았나 — 답을 받고도 안 적었다</h2>
 *
 * <p>소분류는 <b>브랜드</b> 아니면 <b>업종 이름</b>에서 온다. 브랜드가 안 붙는 개인 상호
 * ({@code 나복집}·{@code 안녕숯불}·{@code 팜스퀘어약국})는 업종 이름이 유일한 단서인데,
 * {@code rememberGuess} 가 모델의 답에서 중분류만 계산하고 <b>이름을 버렸다.</b>
 *
 * <p>운영 실측(2026-08-25): 브랜드도 소분류도 없는 사전 행 <b>280곳 중 260곳</b>에 업종 이름이
 * 아예 없었다. 그중 <b>67곳이 {@code LLM_GUESS}</b> — 모델은 답했는데 우리가 안 적은 것이다.
 *
 * <p>관로는 고쳤지만 <b>이미 굳은 행은 저절로 안 낫는다.</b> 사전이 답을 아는 것으로 쳐서
 * {@code plan()} 이 질문 목록에서 빼기 때문이다. 그래서 여기서 한 번 훑어 다시 묻는다.
 *
 * <h2>중분류를 건드리지 않는다</h2>
 *
 * <p>채우는 것은 <b>업종 이름과 소분류</b>뿐이다. 되메우기가 중분류까지 갈아 끼우면 사람이
 * 손으로 정한 것과 종결된 것까지 흔들린다 — 이것은 <b>빠진 세부를 채우는 일</b>이지 분류를
 * 다시 하는 일이 아니다. 소분류가 중분류와 어긋나면 안 찍고 세기만 한다
 * ({@code MerchantCategoryService#agreesWithMid} 와 같은 규칙).
 *
 * <h2>두 번 이상 부른다 — 무료 통로가 비동기라서</h2>
 *
 * <p>{@link TempClassifierService#classify} 는 <b>있으면 주고 없으면 큐에 올린다.</b> 그래서
 * 한 번 부르면 대개 0곳이 채워지고 질문만 올라간다. 큐가 답을 캐시에 넣은 뒤 다시 부르면
 * 그때 채워진다. {@code remaining} 이 0이 될 때까지 누르는 문이다 — 화면이 미분류를 채우는
 * 방식과 같은 규율이고, 새 규율이 아니다.
 */
@Service
public class IndustryNameBackfill {

    private static final Logger log = LoggerFactory.getLogger(IndustryNameBackfill.class);

    /**
     * 한 번에 다룰 최대 행 수.
     *
     * <p>무료 통로는 가맹점 하나가 일 하나라 이 수만큼 큐에 올라간다. 운영 대상이 260곳이라
     * 두 번이면 다 올라가는데, 한 번에 다 밀어 넣으면 <b>화면을 열고 있는 사람의 질문이
     * 뒤로 밀린다</b> — 되메우기는 급하지 않다.
     */
    private static final int BATCH = 150;

    /** 표본으로 남길 최대 줄 수. 다 남기면 응답이 로그가 된다. */
    private static final int SAMPLE_LIMIT = 40;

    private final MerchantCategoryRepository dictionary;
    private final TempClassifierService temporary;
    private final IndustryCategoryMapper industries;

    public IndustryNameBackfill(MerchantCategoryRepository dictionary,
                                TempClassifierService temporary,
                                IndustryCategoryMapper industries) {
        this.dictionary = dictionary;
        this.temporary = temporary;
        this.industries = industries;
    }

    /**
     * @param scanned   업종 이름이 없어 대상이 된 행
     * @param answered  이번에 모델의 답을 받은 행
     * @param stamped   그 답으로 <b>소분류까지 찍은</b> 행
     * @param disagreed 소분류가 나왔지만 중분류와 어긋나 안 찍은 행 — 사람이 볼 자리다
     * @param remaining 아직 답이 안 온 행. 0이 될 때까지 다시 부른다
     */
    public record Result(boolean dryRun, int scanned, int answered, int stamped,
                         int disagreed, int remaining, List<String> samples) {}

    @Transactional
    public Result run(boolean dryRun) {
        List<MerchantCategory> rows =
                dictionary.findWithoutIndustryName(PageRequest.of(0, BATCH));
        if (rows.isEmpty()) {
            log.info("업종 이름 되메우기 — 대상 없음");
            return new Result(dryRun, 0, 0, 0, 0, 0, List.of());
        }
        // 정렬 고정 — 질의가 id 순이라 같은 입력에 같은 묶음이 나온다(§4 원칙 3).
        List<String> names = rows.stream().map(MerchantCategory::getMerchantName).distinct().toList();
        Map<String, TempClassifierService.Guess> got =
                temporary.classify(names, Lane.USER_BACKGROUND);

        List<String> samples = new ArrayList<>();
        int answered = 0, stamped = 0, disagreed = 0;
        for (MerchantCategory row : rows) {
            TempClassifierService.Guess g = got.get(row.getMerchantName());
            if (g == null || g.industryName() == null || g.industryName().isBlank()) continue;
            answered++;
            String sub = industries.subOfIndustryName(g.industryName());
            String expected = sub.isEmpty() ? "" : industries.midOfSub(sub);
            boolean fits = !sub.isEmpty()
                    && (IndustryCategoryMapper.isUnknown(expected) || expected.equals(row.getCategory2()));
            if (!sub.isEmpty() && !fits) {
                disagreed++;
                if (samples.size() < SAMPLE_LIMIT) {
                    samples.add("%s : %s → 소분류 %s 는 %s 인데 이 행은 %s (안 찍음)".formatted(
                            row.getMerchantName(), g.industryName(), sub, expected, row.getCategory2()));
                }
                if (!dryRun) row.noteLlmIndustry(g.industryName());   // 이름은 남긴다 — 근거다
                continue;
            }
            if (samples.size() < SAMPLE_LIMIT) {
                samples.add("%s : %s → %s".formatted(
                        row.getMerchantName(), g.industryName(), sub.isEmpty() ? "(표에 없는 업종)" : sub));
            }
            if (dryRun) { if (fits) stamped++; continue; }
            row.noteLlmIndustry(g.industryName());
            if (fits) { row.applySub(sub); stamped++; }
        }
        int remaining = rows.size() - answered;
        log.info("업종 이름 되메우기{} — 대상 {} · 답 받음 {} · 소분류 찍음 {} · 어긋남 {} · 남음 {}",
                dryRun ? "(맛보기)" : "", rows.size(), answered, stamped, disagreed, remaining);
        return new Result(dryRun, rows.size(), answered, stamped, disagreed, remaining, samples);
    }
}
