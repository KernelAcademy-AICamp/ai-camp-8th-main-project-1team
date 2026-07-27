/**
 * 리포트 &gt; 이상 소비 — 결제별 ML 낭비/필수 판정(§W8)과 규칙 기반 통계 탐지(baseline)를 나란히 둔다.
 * ML은 긴 이력 없이도 거래마다 판정하고 '왜'를 함께 말한다. 규칙 FDS는 이력이 쌓여야 발화한다.
 */
import { useState } from 'react';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty, SectionTitle } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, catLabel, RULE_LABEL, type WasteJudgment } from '../lib/api';
import { won, shortDateTime } from '../lib/format';

function JudgeCard({ j, waste }: { j: WasteJudgment; waste: boolean }) {
  return (
    <div className="judge">
      <div className="judge-top">
        <span className="cat">{j.category2 ?? '기타'}</span>
        <span className="amt">{won(j.amount)}</span>
      </div>
      <p className="when">🕐 {shortDateTime(j.date)}</p>
      <div className="tags">
        {waste && <span className="mchip c-red">낭비</span>}
        <span className="aux-badge">{j.explanation}</span>
        <span className="aux-badge blue">낭비 확률 {Math.round(j.wasteProbability * 100)}%</span>
      </div>
    </div>
  );
}

export function ReportWaste() {
  const { back, userId } = useSession();
  const waste = useAsync(() => api.mlWaste(userId), [userId]);
  const alerts = useAsync(() => api.alerts(userId).catch(() => null), [userId]);
  const [rescanning, setRescanning] = useState(false);

  async function rescan() {
    setRescanning(true);
    try { await api.rescan(userId); alerts.reload(); } catch { /* 조용히 */ }
    finally { setRescanning(false); }
  }

  const list = waste.data ?? [];
  const hits = list.filter((j) => j.waste);
  const watch = list.filter((j) => !j.waste)
    .sort((a, b) => b.wasteProbability - a.wasteProbability).slice(0, 4);

  return (
    <Screen title="이상 소비" hasTabBar>
      <AppBar onBack={back} title="이상 소비" action={
        <button type="button" className="act" onClick={() => void rescan()} disabled={rescanning}>
          {rescanning ? '검사 중…' : '다시 검사'}
        </button>
      } />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        <p className="h-sub" style={{ margin: '0 0 12px' }}>
          AI가 결제마다 ‘낭비 vs 필수’를 판정하고 <b>왜 그렇게 봤는지</b>를 함께 알려줘요.
          {list.length > 0 && ` 결제 ${list.length.toLocaleString('ko-KR')}건 중 낭비 ${hits.length}건.`}
        </p>

        <ErrorBox error={waste.error} onRetry={waste.reload} />
        {waste.loading && <Loading label="판정을 불러오는 중" rows={5} />}

        {!waste.loading && !waste.error && (
          hits.length > 0 ? (
            <>
              <SectionTitle aux="AI 판정">낭비로 본 결제</SectionTitle>
              {hits.map((j) => <JudgeCard key={j.paymentId} j={j} waste />)}
            </>
          ) : (
            <div className="card"><Empty>지금은 낭비로 판정된 소비가 없어요. 👍</Empty></div>
          )
        )}

        {watch.length > 0 && (
          <>
            <SectionTitle aux="낭비 확률 높은 순">주의 깊게 볼 소비</SectionTitle>
            {watch.map((j) => <JudgeCard key={j.paymentId} j={j} waste={false} />)}
          </>
        )}

        {/* 규칙 기반 통계 탐지(baseline) */}
        <SectionTitle aux={alerts.data ? `최근 ${alerts.data.evaluatedCount}건 평가` : undefined}>
          규칙 기반 탐지
        </SectionTitle>
        <div className="card">
          {alerts.data && alerts.data.items.length > 0 ? (
            alerts.data.items.map((a) => (
              <div className="txn" key={a.alertId}>
                <span className="m">{catLabel(a.categoryCode)}</span>
                <span className="c">{a.matchedRules.map((r) => RULE_LABEL[r] ?? r).join(' · ')}</span>
                <span className="a">{won(a.amount)}</span>
              </div>
            ))
          ) : (
            <Empty>
              규칙 탐지는 긴 이력이 있어야 발화해 신규 연동 직후엔 조용해요 —
              그래서 거래별 AI 판정이 먼저 잡습니다.
            </Empty>
          )}
        </div>

        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
