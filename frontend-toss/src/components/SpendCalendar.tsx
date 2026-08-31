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
import { useMemo, useState, type ReactNode } from 'react';

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
  /** 달력 머리 오른쪽 끝에 얹을 것(개편안은 여기에 검색 버튼을 둔다). */
  children?: ReactNode;
}

export function SpendCalendar({ today, totalsByDate, keptDates, selected, onSelect, children }: SpendCalendarProps) {
  const [expanded, setExpanded] = useState(false);
  const base = useMemo(() => new Date(`${today}T00:00:00`), [today]);
  /**
   * 지금 보고 있는 달. 0이 이번 달, 1이 지난 달.
   *
   * <b>지난 달로 넘어갈 수 있어야 한다.</b> 개편안의 달력 머리에는 좌우 화살표가 있는데
   * 이 앱에는 없어서, 이번 달 말고는 달력으로 갈 길이 없었다.
   */
  const [monthsBack, setMonthsBack] = useState(0);
  /** 보고 있는 달의 1일. 접힘/펼침과 무관하게 이 달이 기준이다. */
  const view = useMemo(
    () => new Date(base.getFullYear(), base.getMonth() - monthsBack, 1), [base, monthsBack]);

  const { cells, leadBlanks, tailBlanks, label } = useMemo(() => {
    const label = `${view.getMonth() + 1}월`;
    if (expanded) {
      const last = new Date(view.getFullYear(), view.getMonth() + 1, 0);
      const lead = view.getDay();
      return {
        leadBlanks: lead,
        cells: Array.from({ length: last.getDate() }, (_, i) =>
          new Date(view.getFullYear(), view.getMonth(), i + 1)),
        /* 달마다 줄 수가 달라(4~6줄) 달을 넘길 때 아래 목록이 위아래로 튄다.
           남는 칸을 빈칸으로 채워 **언제나 6줄**로 만든다. */
        tailBlanks: 42 - lead - last.getDate(),
        label,
      };
    }
    /* 접힌 상태 — 한 줄만 보인다.
       이번 달이면 **오늘이 든 주**, 지난 달이면 **그 달 마지막 주**를 보인다.
       지난 달에 '이번 주'는 없지만, 그렇다고 강제로 펼치면 접을 길이 사라진다 —
       달을 옮겼다고 접기 손잡이가 없어지는 것은 사용자가 한 적 없는 결정이다. */
    const anchor = monthsBack === 0
      ? base
      : new Date(view.getFullYear(), view.getMonth() + 1, 0);
    const start = new Date(anchor.getTime() - anchor.getDay() * DAY);
    return {
      leadBlanks: 0,
      cells: Array.from({ length: 7 }, (_, i) => new Date(start.getTime() + i * DAY)),
      tailBlanks: 0,
      label,
    };
  }, [base, view, expanded, monthsBack]);

  return (
    <div className="cal">
      <div className="cal-head">
        <button type="button" aria-label="이전 달"
          onClick={() => setMonthsBack((v) => v + 1)}>‹</button>
        <b>{label}</b>
        {/* 아직 오지 않은 달로는 못 간다 — 빈 달력만 보여 주게 된다. */}
        <button type="button" aria-label="다음 달" disabled={monthsBack === 0}
          onClick={() => setMonthsBack((v) => Math.max(0, v - 1))}>›</button>
        {children}
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
        {Array.from({ length: Math.max(0, tailBlanks) }, (_, i) => (
          <span key={`tail${i}`} className="day mut" aria-hidden="true" />
        ))}
      </div>
      <div className={`cal-leg${expanded ? ' show' : ''}`}>
        <span><i className="lg-td" />오늘</span>
        <span><i className="lg-sel" />선택한 날짜</span>
        <span><i className="lg-kp" />지킨 날</span>
      </div>
      <button
        type="button"
        className="cal-hd"
        aria-label={expanded ? '달력 접기' : '달력 펼치기'}
        aria-expanded={expanded}
        onClick={() => setExpanded((v) => !v)}
      >
        <i />
      </button>
    </div>
  );
}
