/**
 * 리포트 탭 허브 (RP-01 주간 리포트 성격) — "이번 주·이번 달에는 어떻게 지켰는가"에 답한다.
 * 지금 지키는 금액 · 소비 건강 점수 · 절약 리포트 한 문단을 얹고, 자세한 화면들로 보낸다(IA §1.1).
 */
import { useState } from 'react';
import { Orb, Scroll, Screen, ErrorBox, Loading, SectionTitle } from '../components/ui';
import { ScoreGauge, Factor } from '../components/ScoreGauge';
import { useSession, type ScreenId } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';
import { won, pctNum } from '../lib/format';

/** 소비 성격 3종의 색 — 개편안 원본 값. */
const LABEL_COLOR = ['#00804A', '#D97B22', '#8B7BC0'];
/** "7.14~7.20" */
const fmtRange = (a: string, b: string) =>
  `${Number(a.slice(5, 7))}.${Number(a.slice(8, 10))}~${Number(b.slice(5, 7))}.${Number(b.slice(8, 10))}`;

const MENU: { id: ScreenId; emoji: string; bg: string; title: string; desc: string }[] = [
  { id: 'r-spending', emoji: '🍩', bg: 'var(--blue-weak)', title: '카테고리별 소비', desc: '어디에 얼마를 썼는지 · 월별 흐름' },
  { id: 'r-analysis', emoji: '🔎', bg: 'var(--c-cafe)', title: '내 소비 분석', desc: '이상소비지수 · 반복 결제 · 언제 쓰나' },
  { id: 'r-cards', emoji: '💳', bg: 'var(--c-taxi)', title: '내 카드', desc: '카드별 실적과 받은 혜택' },
  { id: 'r-account', emoji: '🏧', bg: 'var(--c-cvs)', title: '내 통장', desc: '잔액·월급·이자 · 입출금 내역' },
  { id: 'r-waste', emoji: '⚠️', bg: 'var(--c-shop)', title: '이상 소비', desc: 'AI가 짚은 낭비/필수 판정' },
  { id: 'r-savings', emoji: '🏦', bg: 'var(--green-weak)', title: '통장 비교', desc: '아낀 돈을 어디에 모을까 · 정보성' },
  { id: 'r-compare', emoji: '🎯', bg: 'var(--blue-weak)', title: '맞춤 상품 비교', desc: '소비 패턴과 맞는 순으로 Top 3 · 전부 더미' },
];

export function Report() {
  const { go, userId } = useSession();
  const { home } = useGuardian();
  const [weeksAgo, setWeeksAgo] = useState(0);
  // 챌린지가 없으면 404다 — 리포트 나머지는 멀쩡히 보여야 하므로 조용히 비운다.
  const weekly = useAsync(
    () => api.guardian.weeklyReport(userId, weeksAgo).catch(() => null),
    [userId, weeksAgo],
  );
  const score = useAsync(() => api.score(userId), [userId]);
  const report = useAsync(() => api.report(userId), [userId]);

  const ch = home?.challenge;

  return (
    <Screen title="리포트" hasTabBar>
      <Scroll><div className="pad" style={{ paddingTop: 20 }}>
        {/* 주차 네비게이션 (개편안 `.wk-nav`) — 다음 주는 아직 오지 않아 막는다. */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
          <div style={{ fontSize: 22, fontWeight: 700 }}>주간 리포트</div>
          <div className="wk-nav">
            <button type="button" aria-label="지난주" onClick={() => setWeeksAgo((w) => w + 1)}>‹</button>
            <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--t2)' }}>
              {weekly.data?.weekLabel ?? '이번 주'}
            </span>
            <button type="button" aria-label="다음주" disabled={weeksAgo === 0}
              onClick={() => setWeeksAgo((w) => Math.max(0, w - 1))}>›</button>
          </div>
        </div>
        {weekly.data && (
          <div style={{ fontSize: 13, color: 'var(--t3)', marginBottom: 16 }}>
            {fmtRange(weekly.data.weekStart, weekly.data.weekEnd)}, 이번 주를 잘 지켰는지 정리했어요
          </div>
        )}

        {/* 주간 방어율 + 4주 추이 */}
        {weekly.data && weekly.data.trend.some((t) => t.judgedDays > 0) && (
          <div className="card">
            <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 16 }}>
              <div>
                <div style={{ fontSize: 13, color: 'var(--t3)', fontWeight: 600 }}>이번 주 방어율</div>
                <div style={{ fontSize: 36, fontWeight: 700, lineHeight: 1.1, margin: '4px 0 8px' }}>
                  {Math.round(weekly.data.defenseRate * 100)}%
                </div>
                {weekly.data.deltaFromLastWeek !== null && (
                  <span className={weekly.data.deltaFromLastWeek >= 0 ? 'tag-good' : 'tag-warn'}>
                    지난주보다 {weekly.data.deltaFromLastWeek >= 0 ? '+' : ''}
                    {Math.round(weekly.data.deltaFromLastWeek * 100)}%p
                  </span>
                )}
              </div>
              <div className="trend">
                {weekly.data.trend.map((t) => (
                  <div key={t.weekStart} className={`tc${t.current ? ' cur' : ''}`}
                    title={`${t.label} ${Math.round(t.defenseRate * 100)}%`}>
                    {/* 막대 높이는 최소 8px — 0%인 주도 자리는 보여야 '기록이 없다'가 읽힌다. */}
                    <div className="tb" style={{ height: Math.max(8, Math.round(t.defenseRate * 56)) }} />
                    <div className="tl">{t.current ? '이번 주' : t.label.replace(/^\d+월 /, '')}</div>
                  </div>
                ))}
              </div>
            </div>
            <div className="divider" style={{ margin: '16px 0 12px' }} />
            <div style={{ fontSize: 13, color: 'var(--t2)' }}>{weekly.data.headline}</div>
          </div>
        )}

        {/* 소비 성격 — 라벨링 구성비 */}
        {weekly.data && weekly.data.labeledCount > 0 && (
          <>
            <SectionTitle>소비 성격 분석</SectionTitle>
            <div className="card">
              <div style={{ fontSize: 12, color: 'var(--t3)' }}>
                이번 주 라벨링 {weekly.data.labeledCount}건 기준
              </div>
              <div className="stackbar">
                {weekly.data.labels.map((l, i) => (
                  <i key={l.key} style={{ width: `${l.ratio * 100}%`, background: LABEL_COLOR[i] }} />
                ))}
              </div>
              {weekly.data.labels.map((l, i) => (
                <div className="lg-row" key={l.key}>
                  <span className="lg-dot" style={{ background: LABEL_COLOR[i] }} />
                  {l.label} {l.count}건<b>{Math.round(l.ratio * 100)}%</b>
                </div>
              ))}
              {weekly.data.exemptedAmount > 0 && (
                <div className="pv" style={{ marginTop: 12 }}>
                  불가피한 소비로 되돌린 <b>{won(weekly.data.exemptedAmount)}</b>은 저금통에 복원됐어요.
                </div>
              )}
            </div>
          </>
        )}

        {/* 이번 챌린지 — 지키는 금액이 주 지표다 */}
        {ch ? (
          <div className="hero">
            <div className="cap">지금 지키고 있는 돈</div>
            <div className="big">{won(ch.securedSaving)}</div>
            <div className="sub">
              {/* '달성'이 아니라 '지키는 중'이다 — Home.tsx의 히어로 주석 참고. */}
              지킬 돈 {won(ch.targetSaving)} 중 {pctNum(ch.achievementRate)}% 지키는 중 · {ch.categoryLabel}
              {ch.daysLeft > 0 ? ` · D-${ch.daysLeft}` : ''}
            </div>
          </div>
        ) : (
          <div className="guardian">
            <Orb size={34} />
            <div className="msg">
              <b>지킴이</b>
              <p>아직 이번 챌린지를 정하지 않았어요. 홈에서 줄일 카테고리를 골라 시작해요.</p>
            </div>
          </div>
        )}

        {/* 절약 리포트 한 문단 — 문장은 온디맨드 LLM, 숫자는 엔진 */}
        <SectionTitle aux={report.data?.narrativeSource === 'AI' ? '✦ AI 요약' : '고정 템플릿'}>절약 리포트</SectionTitle>
        <div className="card">
          {report.loading && <div className="skeleton" style={{ width: '90%' }} />}
          <ErrorBox error={report.error} onRetry={report.reload} />
          {report.data && (
            <>
              <p style={{ margin: 0, fontSize: 14.5, lineHeight: 1.6, color: 'var(--t1)' }}>{report.data.narrative}</p>
              <div className="pv">
                최근 총지출 <b>{won(report.data.totalSpend)}</b> ·
                줄이면 좋은 소비 {report.data.negative.length}개 · 잘 관리한 소비 {report.data.positive.length}개
              </div>
            </>
          )}
        </div>

        {/* 소비 건강 점수 */}
        <SectionTitle aux={score.data?.dataSourceMode === 'CONFIRMED' ? '실제 소비 데이터' : '참고용 추정치'}>
          소비 건강 점수
        </SectionTitle>
        <div className="card">
          {score.loading && <Loading label="점수를 불러오는 중" rows={2} />}
          <ErrorBox error={score.error} onRetry={score.reload} />
          {score.data && (
            <div style={{ display: 'flex', gap: 18, alignItems: 'center', flexWrap: 'wrap' }}>
              <ScoreGauge score={score.data.score} grade={score.data.grade} />
              <div style={{ flex: 1, minWidth: 190, display: 'flex', flexDirection: 'column', gap: 9 }}>
                <Factor label="저축 진행률" value={score.data.breakdown.savingsProgress} />
                <Factor label="소비 안정성" value={score.data.breakdown.stability} />
                <Factor label="필수 소비 비율" value={score.data.breakdown.plannedRatio} />
                {score.data.estimationReason && (
                  <p className="empty" style={{ margin: 0 }}>{score.data.estimationReason}</p>
                )}
              </div>
            </div>
          )}
        </div>

        {/* 자세히 보기 */}
        <SectionTitle>자세히 보기</SectionTitle>
        <div className="menu">
          {MENU.map((m) => (
            <button type="button" key={m.id} className="menu-item" onClick={() => go(m.id)}>
              <span className="mi-ic" style={{ background: m.bg }} aria-hidden="true">{m.emoji}</span>
              <span className="mi-tx"><b>{m.title}</b><span>{m.desc}</span></span>
              <span className="chev" aria-hidden="true">›</span>
            </button>
          ))}
        </div>

        <p className="empty">
          표시되는 금융상품 정보는 <b>정보성 비교</b>일 뿐 판매·중개가 아니에요. 가입은 각 금융사에서 진행해요.
        </p>
        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
