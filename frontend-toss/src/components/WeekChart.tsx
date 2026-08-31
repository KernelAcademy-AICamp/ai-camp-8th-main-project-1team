/**
 * 주간 리포트 차트 — 요일별 막대와 주별 곡선이 같은 자리를 나눠 쓴다 (프로토타입_0806 `s-report`).
 *
 * <b>왜 두 모드인가.</b> 주별 추이는 "나아지고 있나"에 답하고, 요일별은 "언제 무너지나"에 답한다.
 * 금요일마다 새는 사람과 주말에 몰아 쓰는 사람은 주별 막대가 똑같이 생겼는데 고쳐야 할 것이 다르다.
 *
 * <b>계산은 서버가, 그림만 여기서.</b> 값은 `/api/guardian/report/weekly` 가 완성해 내려준다
 * (마스터 §4 원칙 2). 여기서 하는 계산은 <b>화면에 그릴 좌표</b>뿐이다 — 최댓값 대비 높이와
 * 곡선의 제어점. 금액을 더하거나 비율을 내지 않는다.
 */
import { useLayoutEffect, useRef, useState } from 'react';
import { won } from '../lib/format';

export interface DayPoint {
  date: string;
  label: string;
  amount: number;
  kept: boolean;
  /** 판정이 없는 날. 막대를 비워 그린다 — 빼면 월~일이 어긋난다. */
  judged: boolean;
}
export interface WeekPoint { weekStart: string; label: string; defenseRate: number; current: boolean }

/** 카트멀롬 스플라인 — 점만 이으면 꺾여 보인다. 제어점을 이웃 점에서 유도해 부드럽게 만든다. */
function smooth(pts: [number, number][]): string {
  if (pts.length < 2) return '';
  let d = `M${pts[0][0].toFixed(1)} ${pts[0][1].toFixed(1)}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[Math.max(0, i - 1)], p1 = pts[i];
    const p2 = pts[i + 1], p3 = pts[Math.min(pts.length - 1, i + 2)];
    const c1 = [p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6];
    const c2 = [p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6];
    d += ` C${c1[0].toFixed(1)} ${c1[1].toFixed(1)} ${c2[0].toFixed(1)} ${c2[1].toFixed(1)}`
      + ` ${p2[0].toFixed(1)} ${p2[1].toFixed(1)}`;
  }
  return d;
}

const H = 104;

/** 요일별 — 막대 + 고른 날 말풍선. */
function Bars({ days, sel, onPick }: { days: DayPoint[]; sel: number; onPick: (i: number) => void }) {
  const chart = useRef<HTMLDivElement>(null);
  const tip = useRef<HTMLDivElement>(null);
  const [tipPos, setTipPos] = useState<{ left: number; bottom: number; ax: number } | null>(null);

  /**
   * 막대 높이의 기준.
   *
   * <b>`judged` 가 아니라 금액이 있는 날을 본다.</b> `judged` 는 "하루가 끝나 판정이 났는가"라
   * <b>오늘은 언제나 false</b>다. 그것으로 막대를 가르면 오늘 쓴 돈이 차트에서 사라져,
   * 소비 내역에는 보이는데 리포트는 빈 그래프가 된다(2026-08-06 실측).
   */
  const shown = days.filter((d) => d.judged || d.amount > 0);
  const max = Math.max(...shown.map((d) => d.amount), 1) * 1.05;

  // 말풍선은 **그린 뒤 실측해서** 놓는다. 글자 폭이 값에 따라 달라져 미리 계산할 수 없고,
  // 좌우가 잘리면 안 되므로 차트 폭 안으로 밀어 넣는다. 꼬리는 막대 중심을 계속 가리킨다.
  useLayoutEffect(() => {
    const c = chart.current, t = tip.current;
    const d = days[sel];
    if (!c || !t || sel < 0 || !d || (!d.judged && d.amount === 0)) { setTipPos(null); return; }
    const col = c.querySelectorAll<HTMLElement>('.bcol')[sel];
    if (!col) return;
    const bx = col.getBoundingClientRect(), cx = c.getBoundingClientRect();
    const center = bx.left - cx.left + bx.width / 2;
    const left = Math.max(0, Math.min(c.clientWidth - t.offsetWidth, center - t.offsetWidth / 2));
    const barH = col.querySelector<HTMLElement>('.bar')?.offsetHeight ?? 0;
    setTipPos({ left, bottom: barH + 16, ax: center - left });
  }, [sel, days]);

  const cur = days[sel];
  return (
    <>
      <div className="chart" ref={chart}>
        <div className="bars">
          {days.map((d, i) => {
            // 쓴 돈이 있으면 판정 전이라도 그린다 — 오늘 막대가 없으면 "안 썼다"로 읽힌다.
            const h = d.amount > 0
              ? `${Math.max(6, (d.amount / max) * 100).toFixed(1)}%`
              : '4px';
            return (
              <button type="button" key={d.date} disabled={!d.judged && d.amount === 0}
                className={`bcol${i === sel ? ' sel' : ''}${d.judged || d.amount > 0 ? '' : ' off'}`}
                onClick={() => onPick(i)}
                aria-label={`${d.label}요일 ${d.judged || d.amount > 0 ? won(d.amount)
                  : '판정 없음'}${!d.judged && d.amount > 0 ? ' (오늘, 아직 집계 중)' : ''}`}>
                <div className="bar" style={{ height: h }} />
              </button>
            );
          })}
        </div>
        {cur?.judged && (
          <div className="btip" ref={tip} role="status"
            style={tipPos
              ? { left: tipPos.left, bottom: tipPos.bottom, ['--ax' as string]: `${tipPos.ax}px` }
              : { visibility: 'hidden' }}>
            <small>{cur.date.slice(5).replace('-', '월 ')}일</small>
            <b>{won(cur.amount)}</b>
          </div>
        )}
      </div>
      <div className="xlab">
        {days.map((d, i) => (
          <span key={d.date} className={i === sel ? 'sel' : undefined}>{d.label}</span>
        ))}
      </div>
    </>
  );
}

/** 주별 — 지난 흐름(점선)과 이번 흐름(실선)을 겹쳐 그린다. */
function Lines({ trend }: { trend: WeekPoint[] }) {
  const box = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(287);
  useLayoutEffect(() => { if (box.current) setW(box.current.clientWidth || 287); }, [trend]);

  // 방어율(0~1)을 그대로 높이로 쓴다. 마지막 주가 '이번', 그 앞이 '지난 흐름'이다.
  const pts = trend.map((t, i): [number, number] => [
    8 + (w - 16) * (trend.length < 2 ? 0.5 : i / (trend.length - 1)),
    H - 8 - t.defenseRate * (H - 16),
  ]);
  const past = pts.slice(0, Math.max(1, pts.length - 1));
  const end = pts[pts.length - 1];

  return (
    <>
      <div className="chart" ref={box}>
        <svg className="line" viewBox={`0 0 ${w} ${H}`} aria-hidden="true">
          <path d={smooth(past)} fill="none" stroke="#C4CACA" strokeWidth="2"
            strokeDasharray="5 5" strokeLinecap="round" />
          <path d={smooth(pts)} fill="none" stroke="var(--blue)" strokeWidth="2.5" strokeLinecap="round" />
          {past.length > 0 && (
            <circle cx={past[past.length - 1][0].toFixed(1)} cy={past[past.length - 1][1].toFixed(1)}
              r="4" fill="#B9BFBC" />
          )}
          {end && <circle cx={end[0].toFixed(1)} cy={end[1].toFixed(1)} r="4.5" fill="var(--blue)" />}
        </svg>
      </div>
      <div className="lgd">
        <span><i style={{ background: '#B9BFBC' }} />지난 주</span>
        <span><i style={{ background: 'var(--blue)' }} />이번 주</span>
      </div>
    </>
  );
}

/**
 * @param mode  0 요일별 · 1 주별
 * @param lead  차트 위 한 줄 요약 — 라벨과 값을 서버 값에서 만들어 넘긴다
 */
export function WeekChart({ mode, onMode, days, trend, lead }: {
  mode: 0 | 1;
  onMode: (m: 0 | 1) => void;
  days: DayPoint[];
  trend: WeekPoint[];
  lead: { label: string; value: React.ReactNode };
}) {
  // 고른 날은 **지출이 가장 큰 날**로 시작한다. 첫 칸을 고르면 대개 0원이라 말풍선이 빈다.
  const [sel, setSel] = useState(-1);
  const pick = sel >= 0 ? sel
    : days.reduce((best, d, i) => (d.judged && d.amount > (days[best]?.amount ?? -1) ? i : best), 0);

  return (
    <div className="cmp">
      {/* 0818: 붙은 세그먼트가 아니라 **떨어진 칩**(`fchip`) — 상점·꾸미기와 같은 모양이다. */}
      <div className="fchip" role="tablist">
        {(['요일별', '주별'] as const).map((t, i) => (
          <button type="button" key={t} role="tab" aria-selected={mode === i}
            className={mode === i ? 'on' : undefined} onClick={() => onMode(i as 0 | 1)}>{t}</button>
        ))}
      </div>
      <div className="cmp-lead">
        <div className="cf-lab">{lead.label}</div>
        <div className="cf-val">{lead.value}</div>
      </div>
      {mode === 0
        ? <Bars days={days} sel={pick} onPick={setSel} />
        : <Lines trend={trend} />}
    </div>
  );
}
