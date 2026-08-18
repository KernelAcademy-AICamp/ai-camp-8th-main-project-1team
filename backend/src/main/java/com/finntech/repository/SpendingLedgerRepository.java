package com.finntech.repository;

import com.finntech.domain.SpendingLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 정리된 소비 원장 조회·정리 (V34). */
public interface SpendingLedgerRepository extends JpaRepository<SpendingLedger, String> {

    /** 한 사용자의 한 달 — 이 표가 존재하는 이유인 조회축. 정렬 고정(마스터 §4 원칙 3). */
    List<SpendingLedger> findByUserIdAndMonthKeyOrderByPaidAtAscPaymentIdAsc(Long userId, String monthKey);

    /** 한 사용자 전부 — 재작성이 손에 쥐는 것. */
    List<SpendingLedger> findByUserIdOrderByPaidAtAscPaymentIdAsc(Long userId);

    /** 이 결제들의 줄만 — 판정 결과를 받아 적을 때 명단으로 좁혀 읽는다. */
    List<SpendingLedger> findByPaymentIdIn(List<String> paymentIds);

    /**
     * 한 사용자의 기간 — <b>카드 추천이 겹침을 세는 창</b>.
     *
     * <p>달 단위({@link #findByUserIdAndMonthKeyOrderByPaidAtAscPaymentIdAsc})로는 안 된다.
     * 추천이 보는 창은 "최근 3개월"이라 달 경계와 맞지 않고, 달마다 따로 부르면 같은 질문을
     * 세 번 하는 셈이다.
     *
     * <p><b>카드 추천이 {@code user_payment} 대신 이 표를 읽는 이유</b>는 ① 이 이미 알아낸
     * 것을 다시 캐지 않기 위해서다(마스터 §4 원칙 2 · 09 §2.2). 가맹점명에서 브랜드를 뽑고
     * 결제대행사를 가려내는 일은 ① 이 분류·낭비판정 과정에서 이미 했고, 그 답이 여기 있다 —
     * {@code brand} · {@code paymentAgency} · {@code ntsIndustryCode} · {@code stance}.
     *
     * <p>시작은 포함, 끝은 미포함. 정렬 고정(원칙 3).
     */
    @Query("""
            select l from SpendingLedger l
            where l.userId = :userId and l.paidAt >= :from and l.paidAt < :until
            order by l.paidAt asc, l.paymentId asc
            """)
    List<SpendingLedger> findInPeriod(@Param("userId") Long userId,
                                      @Param("from") LocalDateTime from,
                                      @Param("until") LocalDateTime until);

    /**
     * 그 사용자의 결제 식별자만 — <b>엔티티를 세우지 않는다.</b>
     *
     * <p>정합 맞추기(표에는 있는데 원장에는 없는 줄 찾기)에만 쓰므로 칸이 마흔 개인 엔티티를
     * 수천 개 만들 이유가 없다.
     */
    @Query("select l.paymentId from SpendingLedger l where l.userId = :userId")
    List<String> findPaymentIdsByUserId(@Param("userId") Long userId);

    long countByUserId(Long userId);

    /** 그 사용자에게 있는 달들 — 판정 결과를 달 단위로 나눠 적을 때 쓴다. 정렬 고정. */
    @Query("select distinct l.monthKey from SpendingLedger l where l.userId = :userId order by l.monthKey")
    List<String> findDistinctMonthKeysByUserId(@Param("userId") Long userId);

    /**
     * 고정지출 판정이 지금 사실보다 낡은 줄이 이 사용자에게 있나.
     *
     * <p>판정 결과를 받아 적기 전에 이것부터 본다. 화면을 열 때마다 판정이 도는데 그때마다
     * 수천 줄을 다시 쓰면, <b>표를 위해 일을 만드는 셈</b>이 된다. 바뀔 것이 없으면 안 쓴다.
     */
    @Query("""
            select count(l) > 0 from SpendingLedger l
            where l.userId = :userId
              and (l.fixedRecordedAt is null or l.fixedRecordedAt < l.factsUpdatedAt
                   or l.detectorVersion is null or l.detectorVersion <> :detectorVersion)
            """)
    boolean hasStaleFixed(@Param("userId") Long userId,
                          @Param("detectorVersion") String detectorVersion);

    /**
     * 판정이 낡은 줄을 가진 사용자들 — <b>밤 갱신이 고를 대상</b>.
     *
     * <p>두 층 중 하나라도 낡았으면 그 사용자를 집는다. 낡음의 뜻은 넷이다: 판정이 한 번도 안
     * 돌았거나, 사실이 그 뒤로 바뀌었거나, 판정 규칙 판이 올라갔거나, 모델이 갈렸거나.
     *
     * <p>정렬 고정(마스터 §4 원칙 3) — 예산에 걸려 중간에 끊겨도 다음 회차가 같은 순서로 잇는다.
     */
    @Query("""
            select distinct l.userId from SpendingLedger l
            where l.fixedRecordedAt is null or l.fixedRecordedAt < l.factsUpdatedAt
               or l.detectorVersion is null or l.detectorVersion <> :detectorVersion
               or l.wasteRecordedAt is null or l.wasteRecordedAt < l.factsUpdatedAt
               or l.modelFingerprint is null or l.modelFingerprint <> :modelFingerprint
            order by l.userId
            """)
    List<Long> findUsersWithStaleJudgments(@Param("detectorVersion") String detectorVersion,
                                           @Param("modelFingerprint") String modelFingerprint);

    /** 낭비 판정이 지금 사실보다 낡거나 다른 모델이 낸 줄이 있나. */
    @Query("""
            select count(l) > 0 from SpendingLedger l
            where l.userId = :userId
              and (l.wasteRecordedAt is null or l.wasteRecordedAt < l.factsUpdatedAt
                   or l.modelFingerprint is null or l.modelFingerprint <> :modelFingerprint)
            """)
    boolean hasStaleWaste(@Param("userId") Long userId,
                          @Param("modelFingerprint") String modelFingerprint);

    /** 사용자별 줄 수 — 운영 점검이 원장 건수와 견준다. */
    @Query("select l.userId, count(l) from SpendingLedger l group by l.userId order by l.userId")
    List<Object[]> countGroupedByUserId();

    /** 배포된 판정 규칙이 여럿 섞여 있나 — 옛 판으로 쓰인 줄을 한 질의로 찾는다. */
    @Query("select distinct l.detectorVersion from SpendingLedger l where l.detectorVersion is not null")
    List<String> findDistinctDetectorVersions();

    /** 지금 모델과 다른 회차의 판정이 남아 있나. */
    @Query("select distinct l.modelFingerprint from SpendingLedger l where l.modelFingerprint is not null")
    List<String> findDistinctModelFingerprints();

    /**
     * 사실이 바뀐 뒤로 고정지출 판정이 안 돈 줄 — <b>낡음의 정의</b>.
     *
     * <p>판정이 한 번도 안 돈 줄({@code fixedRecordedAt is null})도 함께 센다. 둘 다 "지금
     * 사실과 짝이 맞는 답이 없다"는 같은 상태다.
     */
    @Query("""
            select count(l) from SpendingLedger l
            where l.fixedRecordedAt is null or l.fixedRecordedAt < l.factsUpdatedAt
            """)
    long countStaleFixed();

    /** 사실이 바뀐 뒤로 낭비 판정이 안 돈 줄. */
    @Query("""
            select count(l) from SpendingLedger l
            where l.wasteRecordedAt is null or l.wasteRecordedAt < l.factsUpdatedAt
            """)
    long countStaleWaste();

    /** 가장 오래 손대지 않은 줄의 시각 — 배수가 멈췄는지 보는 계기판. */
    @Query("select min(l.factsUpdatedAt) from SpendingLedger l")
    LocalDateTime findOldestFactsUpdatedAt();

    /**
     * 벌크 삭제(즉시 DML) — 파기와 "이제 실사용자가 아닌 사람" 정리.
     *
     * <p>{@code deleteAll} 대신 벌크인 이유는 재작성이 지우고 다시 넣는 순서를 타기 때문이다.
     * 영속 컨텍스트에 맡기면 flush 시점이 밀려 순서가 뒤집힌다({@code UserPaymentRepository}
     * 가 같은 이유로 같은 모양이다).
     */
    @Modifying
    @Transactional
    @Query("delete from SpendingLedger l where l.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
