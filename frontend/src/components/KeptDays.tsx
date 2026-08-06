/**
 * 지킨 날 — 이번 주 7칸을 먼저 보이고 월 달력은 접어 둔다 (프로토타입_0806 `s-myroom`).
 *
 * <b>왜 주가 먼저인가.</b> 예전에는 30일 격자를 늘 펼쳐 뒀다. 그러면 화면이 격자로 차서
 * "오늘 지켰나"라는 물음이 묻힌다 — 매일 확인하는 유일한 질문이 그것인데도. 이번 주를 크게
 * 보이고 한 달치는 눌러서 펼치게 한다.
 *
 * <b>오늘은 채우지 않는다.</b> 테두리로만 표시한다. 채우면 '지킨 날'과 같아 보이는데,
 * 오늘은 아직 끝나지 않아 지켰는지 알 수 없다.
 */
import { useState } from 'react';
import { Icon } from '../components/Icons';
import { DAILY_RESULT_LABEL } from '../lib/format';

export interface GrassCell { date: string; result: string; granted: boolean }

const DOW = ['월', '화', '수', '목', '금', '토', '일'];
/** 월요일 시작으로 옮긴 요일 번호 — 리포트의 주차 기준과 같다. */
const mondayIndex = (d: Date) => (d.getDay() + 6) % 7;

/** 지킨 날인가 — 무지출과 페이스 안이 둘 다 '지킴'이다. */
const isKept = (r?: string) => r === 'NO_SPEND_DAY' || r === 'ON_PACE_DAY';

export function KeptDays({ asOf, grass, streak, keptThisMonth }: {
  asOf: string;
  grass: GrassCell[];
  /** 연속 지킨 날. */
  streak: number;
  /** 이번 달 지킨 날 수. */
  keptThisMonth: number;
}) {
  const [open, setOpen] = useState(false);
  const today = new Date(`${asOf.slice(0, 10)}T00:00:00`);
  const byDate = new Map(grass.map((g) => [g.date, g]));
  const iso = (d: Date) => d.toISOString().slice(0, 10);

  // ── 이번 주(월~일)
  const weekStart = new Date(today);
  weekStart.setDate(today.getDate() - mondayIndex(today));
  const week = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(weekStart);
    d.setDate(weekStart.getDate() + i);
    const key = iso(d);
    const g = byDate.get(key);
    return {
      key,
      label: key === iso(today) ? '오늘' : DOW[i],
      state: key === iso(today) ? 'now' : d > today ? 'fut' : isKept(g?.result) ? 'kept' : '',
      result: g?.result,
    };
  });

  // ── 이번 달 전체 (펼쳤을 때만 그린다)
  const first = new Date(today.getFullYear(), today.getMonth(), 1);
  const lead = mondayIndex(first);
  const daysInMonth = new Date(today.getFullYear(), today.getMonth() + 1, 0).getDate();
  const month = Array.from({ length: daysInMonth }, (_, i) => {
    const d = new Date(today.getFullYear(), today.getMonth(), i + 1);
    const key = iso(d);
    const g = byDate.get(key);
    return { key, day: i + 1, result: g?.result, today: key === iso(today), future: d > today };
  });

  return (
    <div className="grass-card">
      <div className="kp-tiles">
        <div className="kp-tile">
          <div className="cap">연속 지킨 날</div>
          <div className="val"><Icon id="i-flame" />{streak}일</div>
        </div>
        <div className="kp-tile">
          <div className="cap">이번 달 지킨 날</div>
          <div className="val" style={{ color: 'var(--blue-t)' }}><Icon id="i-check" />{keptThisMonth}일</div>
        </div>
      </div>

      <div className="kp-week">
        {week.map((d) => (
          <div key={d.key} className={`kp-day ${d.state}`}
            title={`${d.key} · ${d.result ? DAILY_RESULT_LABEL[d.result as keyof typeof DAILY_RESULT_LABEL] : '기록 없음'}`}>
            <span className="kp-c">{d.state === 'kept' && <Icon id="i-check" />}</span>
            <span>{d.label}</span>
          </div>
        ))}
      </div>

      {open && (
        <div>
          <div className="gm">{today.getMonth() + 1}월</div>
          <div className="dow" aria-hidden="true">{DOW.map((d) => <div key={d}>{d}</div>)}</div>
          <div className="ggrid">
            {Array.from({ length: lead }, (_, i) => (
              <div key={`lead${i}`} className="gcell" style={{ visibility: 'hidden' }} />
            ))}
            {month.map((c) => (
              <div key={c.key}
                className={`gcell${isKept(c.result) ? ' g2' : ''}${c.today ? ' today' : ''}${c.future ? ' future' : ''}`}
                title={`${c.key} · ${c.result ? DAILY_RESULT_LABEL[c.result as keyof typeof DAILY_RESULT_LABEL] : '기록 없음'}`}>
                <em>{c.day}</em>
              </div>
            ))}
          </div>
        </div>
      )}

      <button type="button" className={`madd kp-btn${open ? ' open' : ''}`} aria-expanded={open}
        onClick={() => setOpen((v) => !v)}>
        {open ? '접기' : '더보기'}<span className="chev" aria-hidden="true">›</span>
      </button>
    </div>
  );
}
