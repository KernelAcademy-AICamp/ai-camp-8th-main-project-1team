/**
 * 소비 달력 (개편안 `s-spend`의 `.cal`) — 날짜별 지출과 지킨 날을 한눈에.
 *
 * <p><b>접힘이 기본이다.</b> 펼치면 한 달이 다 보이지만 화면의 절반을 먹는다. 사람이 소비 내역을
 * 열 때 가장 궁금한 것은 최근 며칠이라, 기본은 <b>이번 주</b>만 보여주고 손잡이를 누르면 펼친다.
 *
 * <p><b>미래 날짜는 누를 수 없다.</b> 아직 오지 않은 날에는 소비가 없으므로 선택해도 빈 목록만
 * 나온다 — 누를 수 있게 두면 사용자가 자기가 뭘 잘못했는지 찾게 된다.
 *
 * <p>범례는 펼쳤을 때만 보인다. 접힌 주간 뷰에서는 칸이 일곱 개뿐이라 설명 없이도 읽힌다.
 */
import { useMemo, useState } from 'react';

const WD = ['일', '월', '화', '수', '목', '금', '토'];
const DAY = 86_400_000;

/** 로컬 벽시계 기준 YYYY-MM-DD. toISOString()은 UTC라 KST 자정이 전날로 밀린다. */
const iso = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

export interface SpendCalendarProps {
  /** 기준이 되는 '오늘' — 서버가 준 값을 쓴다. 브라우저 시계를 쓰면 데모에서 어긋난다. */
  today: string;
  /** 날짜(YYYY-MM-DD) → 그날 지출 합계. */
  totalsByDate: Record<string, number>;
  /** 지킨 날(YYYY-MM-DD) 집합 — 점으로 표시한다. */
  keptDates: Set<string>;
  selected: string | null;
  onSelect: (date: string | null) => void;
}

export function SpendCalendar({ today, totalsByDate, keptDates, selected, onSelect }: SpendCalendarProps) {
  const [expanded, setExpanded] = useState(false);
  const base = useMemo(() => new Date(`${today}T00:00:00`), [today]);

  const { cells, leadBlanks, label } = useMemo(() => {
    if (expanded) {
      const first = new Date(base.getFullYear(), base.getMonth(), 1);
      const last = new Date(base.getFullYear(), base.getMonth() + 1, 0);
      return {
        leadBlanks: first.getDay(),
        cells: Array.from({ length: last.getDate() }, (_, i) =>
          new Date(base.getFullYear(), base.getMonth(), i + 1)),
        label: `${base.getMonth() + 1}월`,
      };
    }
    // 접힌 상태 — 오늘이 포함된 주(일~토).
    const start = new Date(base.getTime() - base.getDay() * DAY);
    return {
      leadBlanks: 0,
      cells: Array.from({ length: 7 }, (_, i) => new Date(start.getTime() + i * DAY)),
      label: `${base.getMonth() + 1}월`,
    };
  }, [base, expanded]);

  return (
    <div className="cal">
      <div className="cal-head">
        <b>{label}</b>
      </div>
      <div className="cal-grid">
        {WD.map((w, i) => (
          <span key={w} className={`wd${i === 0 ? ' sun' : i === 6 ? ' sat' : ''}`}>{w}</span>
        ))}
        {Array.from({ length: leadBlanks }, (_, i) => (
          <span key={`blank${i}`} className="day mut" />
        ))}
        {cells.map((d) => {
          const key = iso(d);
          const future = key > today;
          const total = totalsByDate[key] ?? 0;
          const cls = [
            'day',
            key === selected ? 'sel' : '',
            key === today ? 'today' : '',
            future ? 'mut' : '',
            keptDates.has(key) ? 'kept' : '',
          ].filter(Boolean).join(' ');
          return (
            <button
              key={key}
              type="button"
              className={cls}
              disabled={future}
              aria-pressed={key === selected}
              aria-label={`${d.getMonth() + 1}월 ${d.getDate()}일${total ? ` 지출 ${total.toLocaleString('ko-KR')}원` : ''}`}
              onClick={() => onSelect(key === selected ? null : key)}
            >
              <span className="dn">{d.getDate()}</span>
              <span className="dv">{total ? `-${total.toLocaleString('ko-KR')}` : ''}</span>
            </button>
          );
        })}
      </div>
      <div className={`cal-leg${expanded ? ' show' : ''}`}>
        <span><i className="lg-td" />오늘</span>
        <span><i className="lg-sel" />선택한 날짜</span>
        <span><i className="lg-kp" />지킨 날</span>
      </div>
      <div
        className="cal-hd"
        role="button"
        tabIndex={0}
        aria-label={expanded ? '달력 접기' : '달력 펼치기'}
        aria-expanded={expanded}
        onClick={() => setExpanded((v) => !v)}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setExpanded((v) => !v); }}
      >
        <i />
      </div>
    </div>
  );
}
