/**
 * 리포트 탭 허브 (RP-01 주간 리포트 성격) — "이번 주·이번 달에는 어떻게 지켰는가"에 답한다.
 * 지금 지키는 금액 · 소비 건강 점수 · 절약 리포트 한 문단을 얹고, 자세한 화면들로 보낸다(IA §1.1).
 */
import { Orb, Scroll, Screen, ErrorBox, Loading, SectionTitle } from '../components/ui';
import { ScoreGauge, Factor } from '../components/ScoreGauge';
import { useSession, type ScreenId } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';
import { won, pctNum } from '../lib/format';

const MENU: { id: ScreenId; emoji: string; bg: string; title: string; desc: string }[] = [
  { id: 'r-spending', emoji: '🍩', bg: 'var(--blue-weak)', title: '카테고리별 소비', desc: '어디에 얼마를 썼는지 · 월별 흐름' },
  { id: 'r-analysis', emoji: '🔎', bg: 'var(--c-cafe)', title: '내 소비 분석', desc: '이상소비지수 · 반복 결제 · 언제 쓰나' },
  { id: 'r-cards', emoji: '💳', bg: 'var(--c-taxi)', title: '내 카드', desc: '카드별 실적과 받은 혜택' },
  { id: 'r-account', emoji: '🏧', bg: 'var(--c-cvs)', title: '내 통장', desc: '잔액·월급·이자 · 입출금 내역' },
  { id: 'r-waste', emoji: '⚠️', bg: 'var(--c-shop)', title: '이상 소비', desc: 'AI가 짚은 낭비/필수 판정' },
  { id: 'r-savings', emoji: '🏦', bg: 'var(--green-weak)', title: '통장 비교', desc: '아낀 돈을 어디에 모을까 · 정보성' },
];

export function Report() {
  const { go, userId } = useSession();
  const { home } = useGuardian();
  const score = useAsync(() => api.score(userId), [userId]);
  const report = useAsync(() => api.report(userId), [userId]);

  const ch = home?.challenge;

  return (
    <Screen title="리포트" hasTabBar>
      <Scroll><div className="pad" style={{ paddingTop: 20 }}>
        <p style={{ fontSize: 21, fontWeight: 800, margin: '0 0 14px' }}>리포트</p>

        {/* 이번 챌린지 — 지키는 금액이 주 지표다 */}
        {ch ? (
          <div className="hero">
            <div className="cap">지금 지키고 있는 돈</div>
            <div className="big">{won(ch.securedSaving)}</div>
            <div className="sub">
              지킬 돈 {won(ch.targetSaving)} 중 {pctNum(ch.achievementRate)}% 달성 · {ch.categoryLabel}
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
