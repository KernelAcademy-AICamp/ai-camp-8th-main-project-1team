package com.finntech.service;

import com.finntech.domain.Report;
import com.finntech.repository.ReportRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 리포트 캐시 쓰기 전용 — <b>부모 트랜잭션과 분리해서</b> 저장한다.
 *
 * <p><b>왜 별도 빈인가.</b> 캐시 저장은 실패해도 응답에 영향이 없어야 한다. 그런데
 * {@link ReportService#buildCached}의 트랜잭션 안에서 INSERT가 unique 제약을 때리면,
 * 예외를 {@code catch}로 삼켜도 그 트랜잭션은 이미 <b>rollback-only로 마킹</b>되어
 * 커밋 시점에 {@code UnexpectedRollbackException}이 터진다(MySQL 기본 REPEATABLE READ).
 * 삼킨 쪽은 "영향 없음"이라고 로그를 남기는데 정작 사용자는 500을 받는, 추적이 어려운 형태다.
 *
 * <p>그래서 저장만 {@link Propagation#REQUIRES_NEW}로 떼어낸다. 실패는 이 안쪽 트랜잭션에서
 * 끝나고 부모는 멀쩡히 커밋한다 — 주석이 약속한 "응답에는 영향 없음"이 그제야 사실이 된다.
 *
 * <p>경쟁이 실제로 일어나는 지점: 사람 교체 연결·재링크로 캐시를 비운 직후 첫 진입.
 * 프론트가 같은 화면에서 리포트를 두 번 부르면(React StrictMode 이중 마운트, 또는 동시 사용자)
 * 두 요청이 같이 없음을 확인하고 같이 INSERT한다. 먼저 커밋한 쪽이 이기고 진 쪽은 여기서 조용히 접는다
 * — 어차피 두 요청의 본문은 같은 계산 결과라 진 쪽이 응답을 못 주는 이유가 없다.
 * (충동절약통 {@code impulse_saver_state} 중복키 500과 같은 유형·2026-07-23.)
 */
@Component
public class ReportCacheWriter {

    private final ReportRepository reportRepository;

    public ReportCacheWriter(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    /**
     * 캐시 한 건 저장. <b>실패하면 던진다</b> — 삼키지 않는 것이 핵심이다.
     *
     * <p>여기서 {@code try/catch}로 감싸도 소용이 없다. INSERT가 제약을 때린 순간 <b>이 트랜잭션도</b>
     * rollback-only가 되어 메서드를 빠져나갈 때 커밋이 {@code UnexpectedRollbackException}을 던진다.
     * doomed 트랜잭션은 안에서 구할 수 없다. 그래서 판단은 호출자가 한다 —
     * 이 트랜잭션이 부모와 분리돼 있다는 것만으로 이미 목적은 달성됐다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(Long userId, String period, String bodyJson, LocalDateTime at) {
        reportRepository.save(new Report(userId, period, bodyJson, at));
    }
}
