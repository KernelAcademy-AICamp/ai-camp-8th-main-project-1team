/**
 * 리포트 &gt; 카테고리별 소비 — 도넛(구성) · 월별 바(흐름) · 카테고리 목록.
 * '줄이면 좋은 소비'와 '잘 관리한 소비'는 서버가 나눠 내려준 것이다(ML 낭비 판정 또는 쏠림 기준).
 */
import { useMemo } from 'react';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty, SectionTitle } from '../components/ui';
import { DonutChart, DonutLegend, BarChart } from '../components/Charts';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, catLabel, type ReportLine } from '../lib/api';
import { won, wonShort, monthLabel } from '../lib/format';

function CatRow({ line, tone, max }: { line: ReportLine; tone: 'good' | 'bad'; max: number }) {
  const color = tone === 'bad' ? 'var(--red)' : 'var(--green)';
  return (
    <div className="bank-row">
      <div className="mid">
        <b>{catLabel(line.categoryCode, line.displayName)}</b>
        <div className="bar">
          <i style={{ width: `${Math.max(6, (line.spendPercent / max) * 100)}%`, background: color }} />
        </div>
      </div>
      <div className="right">
        <b>{wonShort(line.amount)}</b>
        <span>{line.spendPercent}% · {line.count.toLocaleString('ko-KR')}건</span>
      </div>
    </div>
  );
}

export function ReportSpending() {
  const { back, userId } = useSession();
  const report = useAsync(() => api.report(userId), [userId]);

  const { slices, months, maxPct } = useMemo(() => {
    const r = report.data;
    if (!r) return { slices: [], months: [], maxPct: 1 };
    return {
      slices: [...r.negative, ...r.positive]
        .map((l) => ({ label: catLabel(l.categoryCode, l.displayName), value: l.amount }))
        .sort((a, b) => b.value - a.value),
      months: Object.entries(r.monthlySpend).sort(([a], [b]) => a.localeCompare(b)),
      maxPct: Math.max(1, ...[...r.negative, ...r.positive].map((l) => l.spendPercent)),
    };
  }, [report.data]);

  return (
    <Screen id="spend" title="카테고리별 소비" hasTabBar>
      <AppBar onBack={back} title="카테고리별 소비" />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        <ErrorBox error={report.error} onRetry={report.reload} />
        {report.loading && <Loading label="소비 현황을 불러오는 중" rows={6} />}

        {report.data && (
          <>
            <SectionTitle aux={report.data.dataSourceMode === 'CONFIRMED' ? '실제 소비 데이터' : '참고용 추정치'}>
              어디에 썼나
            </SectionTitle>
            <div className="card">
              {slices.length === 0 ? <Empty>아직 소비 데이터가 없어요.</Empty> : (
                <div className="donut-wrap">
                  <DonutChart slices={slices.slice(0, 8)} centerLabel={wonShort(report.data.totalSpend)} />
                  <DonutLegend slices={slices.slice(0, 8)} />
                </div>
              )}
            </div>

            {months.length > 1 && (
              <>
                <SectionTitle aux={`${months.length}개월`}>월별 지출</SectionTitle>
                <div className="card">
                  <BarChart bars={months.map(([m, v]) => ({ label: m.slice(5), value: v }))} />
                  <div className="pv">
                    가장 많이 쓴 달은 <b>{monthLabel(months.reduce((a, b) => (b[1] > a[1] ? b : a))[0])}</b>이에요.
                  </div>
                </div>
              </>
            )}

            {report.data.negative.length > 0 && (
              <>
                <SectionTitle aux="AI·규칙 판정">줄이면 좋은 소비</SectionTitle>
                <div className="bank-list">
                  {report.data.negative.map((l) => (
                    <CatRow key={l.categoryCode} line={l} tone="bad" max={maxPct} />
                  ))}
                </div>
              </>
            )}

            {report.data.positive.length > 0 && (
              <>
                <SectionTitle>잘 관리한 소비</SectionTitle>
                <div className="bank-list">
                  {report.data.positive.map((l) => (
                    <CatRow key={l.categoryCode} line={l} tone="good" max={maxPct} />
                  ))}
                </div>
              </>
            )}

            <div className="pv">
              전체 지출 <b>{won(report.data.totalSpend)}</b>
              {report.data.estimationReason && <> · {report.data.estimationReason}</>}
            </div>
          </>
        )}
        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
