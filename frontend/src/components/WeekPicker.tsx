/**
 * 주차 선택 시트 (프로토타입_0806 `#tpSheet`) — 연·월·주 세 휠.
 *
 * <b>왜 화살표만으로는 안 되나.</b> 리포트는 ‹ › 로 한 주씩 옮길 수 있는데, 석 달 전을 보려면
 * 열두 번을 눌러야 한다. 지난 기록을 훑는 화면에서 그건 사실상 못 가는 것과 같다.
 *
 * <b>스크롤이 곧 선택이다.</b> 가운데 띠에 온 값이 고른 값이며, 눌러도 그 자리로 굴러간다.
 * 스크롤이 멈춘 뒤(120ms) 어느 칸에 섰는지 계산한다 — 굴러가는 도중에 값을 확정하면
 * 지나치는 숫자마다 아래 휠이 다시 만들어져 손끝이 튄다.
 *
 * <b>없는 미래는 만들지 않는다.</b> 올해를 고르면 이번 달까지, 이번 달을 고르면 이번 주까지만
 * 굴러간다. 아직 오지 않은 주를 고를 수 있으면 "리포트가 비었다"는 화면을 스스로 만드는 셈이다.
 */
import { useEffect, useRef } from 'react';

/** 한 칸 높이(px). CSS `.tp-it` 와 같아야 스크롤 위치 계산이 맞는다. */
const ROW = 40;

export interface WeekSel { y: number; m: number; w: number }

/** 그 달의 1일이 속한 주부터 센 주차 수. 리포트의 주 기준(월요일 시작)과 같다. */
function weekCount(y: number, m: number): number {
  const last = new Date(y, m, 0).getDate();
  const lead = (new Date(y, m - 1, 1).getDay() + 6) % 7;   // 월요일 시작으로 옮긴 요일
  return Math.ceil((lead + last) / 7);
}

/** 날짜가 그 달의 몇 주차인가. */
export function weekOfMonth(d: Date): number {
  const lead = (new Date(d.getFullYear(), d.getMonth(), 1).getDay() + 6) % 7;
  return Math.floor((lead + d.getDate() - 1) / 7) + 1;
}

/** 고른 (연,월,주)의 월요일. */
export function mondayOf(sel: WeekSel): Date {
  const first = new Date(sel.y, sel.m - 1, 1);
  const lead = (first.getDay() + 6) % 7;
  const d = new Date(sel.y, sel.m - 1, 1 + (sel.w - 1) * 7 - lead);
  return d;
}

function Wheel({ id, values, unit, value, onSettle }: {
  id: string; values: number[]; unit: string; value: number; onSettle: (v: number) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const timer = useRef<number | undefined>(undefined);

  // 밖에서 값이 바뀌면(위 휠을 굴려 목록이 새로 만들어졌을 때) 그 자리로 옮긴다.
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const i = Math.max(0, values.indexOf(value));
    if (Math.round(el.scrollTop / ROW) !== i) el.scrollTop = i * ROW;
  }, [value, values]);

  return (
    <div className="tp-col" ref={ref} role="listbox" aria-label={id}
      onScroll={() => {
        const el = ref.current;
        if (!el) return;
        window.clearTimeout(timer.current);
        timer.current = window.setTimeout(() => {
          const i = Math.max(0, Math.min(values.length - 1, Math.round(el.scrollTop / ROW)));
          if (values[i] !== value) onSettle(values[i]);
        }, 120);
      }}>
      <div className="tp-pad" />
      {values.map((v) => (
        <button type="button" key={v} className={`tp-it${v === value ? ' on' : ''}`}
          role="option" aria-selected={v === value}
          onClick={() => ref.current?.scrollTo({ top: values.indexOf(v) * ROW, behavior: 'smooth' })}>
          {v}{unit}
        </button>
      ))}
      <div className="tp-pad" />
    </div>
  );
}

export function WeekPicker({ open, sel, today, onChange, onClose, onConfirm }: {
  open: boolean;
  sel: WeekSel;
  /** '지금'의 기준일 — 데모 시계를 쓰면 실제 오늘과 다르다. */
  today: Date;
  onChange: (s: WeekSel) => void;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const curY = today.getFullYear();
  const curM = today.getMonth() + 1;
  const curW = weekOfMonth(today);

  // 아직 오지 않은 달·주는 목록에서 뺀다.
  const years = [curY - 2, curY - 1, curY];
  const months = Array.from({ length: sel.y === curY ? curM : 12 }, (_, i) => i + 1);
  const weeks = Array.from(
    { length: sel.y === curY && sel.m === curM ? curW : weekCount(sel.y, sel.m) },
    (_, i) => i + 1);

  return (
    <>
      <div className={`tp-dim${open ? ' show' : ''}`} onClick={onClose} aria-hidden="true" />
      <div className={`tp-sheet${open ? ' show' : ''}`} role="dialog" aria-label="주차 고르기"
        aria-hidden={!open}>
        <div className="tp-head">어느 주를 볼까요</div>
        <div className="tp-wheels">
          <div className="tp-band" aria-hidden="true" />
          <Wheel id="연도" values={years} unit="년" value={sel.y}
            onSettle={(y) => onChange(clamp({ ...sel, y }, curY, curM, curW))} />
          <Wheel id="월" values={months} unit="월" value={sel.m}
            onSettle={(m) => onChange(clamp({ ...sel, m }, curY, curM, curW))} />
          <Wheel id="주차" values={weeks} unit="주차" value={sel.w}
            onSettle={(w) => onChange({ ...sel, w })} />
        </div>
        <button type="button" className="btn btn-primary" style={{ padding: 16 }}
          onClick={onConfirm}>이 주 보기</button>
      </div>
    </>
  );
}

/** 위 휠을 굴려 아래 목록이 짧아졌을 때, 사라진 자리에 서 있지 않게 당긴다. */
function clamp(s: WeekSel, curY: number, curM: number, curW: number): WeekSel {
  const maxM = s.y === curY ? curM : 12;
  const m = Math.min(s.m, maxM);
  const maxW = s.y === curY && m === curM ? curW : weekCount(s.y, m);
  return { y: s.y, m, w: Math.min(s.w, maxW) };
}
