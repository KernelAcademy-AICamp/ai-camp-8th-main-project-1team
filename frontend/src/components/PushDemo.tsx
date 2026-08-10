/**
 * 발표용 푸시 배너 (프로토타입_0806 `.push-layer`).
 *
 * <b>무엇을 보이려는 장치인가.</b> 카드사 문자와 MOA 알림을 <b>나란히</b> 띄운다. 앞은
 * "승인 윤*정님 23,500원 일시불", 뒤는 "배달 결제 23,500원을 확인했어요 · 이번 주 배달
 * 예산이 8,500원 남았어요". 같은 결제 하나를 두고 기계의 말과 사람의 말이 어떻게 다른지가
 * 이 앱의 논지라, 말로 설명하는 것보다 두 장을 겹쳐 보이는 편이 빠르다.
 *
 * <b>왜 시간 차를 두나.</b> 카드사 문자가 먼저 오고 MOA 가 뒤따르는 것이 실제 순서다. 동시에
 * 띄우면 MOA 가 문자를 대체하는 것처럼 보이는데, 실제로는 <b>해석해 얹는</b> 쪽이다.
 *
 * <b>실제 푸시가 아니다.</b> 이 앱은 아직 푸시 권한을 받지 않으며, 여기 배너는 화면 안에
 * 그린 그림이다. 그래서 데모 도구에서만 켠다 — 아무 데서나 뜨면 진짜 푸시로 오해한다.
 */
import { useEffect, useRef, useState } from 'react';

interface Card { id: string; badge: string; badgeStyle: React.CSSProperties; title: string; body: string }

const CARDS: Card[] = [
  {
    id: 'card',
    badge: 'KB',
    badgeStyle: { background: '#FFBC00', color: '#1c1f24' },
    title: 'KB국민카드',
    body: '승인 윤*정님 23,500원 일시불 07/24 11:52 배달의민족',
  },
  {
    id: 'moa',
    badge: 'MOA',
    badgeStyle: { background: 'var(--blue)' },
    title: '배달 결제 23,500원을 확인했어요',
    body: '이번 주 배달 예산이 8,500원 남았어요',
  },
];

/** 등장·퇴장 시각(ms) — 개편안 원본 그대로. */
const SCHEDULE: Record<string, { in: number; out: number }> = {
  card: { in: 500, out: 4000 },
  moa: { in: 2000, out: 7000 },
};

export function PushDemo({ open, onTapMoa, onDone }: {
  open: boolean;
  /** MOA 배너를 눌렀을 때 — 알림함으로 보낸다. */
  onTapMoa: () => void;
  /** 마지막 배너까지 사라졌을 때. */
  onDone: () => void;
}) {
  const [shown, setShown] = useState<Set<string>>(new Set());
  const timers = useRef<number[]>([]);

  useEffect(() => {
    timers.current.forEach(window.clearTimeout);
    timers.current = [];
    if (!open) { setShown(new Set()); return; }

    const at = (ms: number, fn: () => void) => timers.current.push(window.setTimeout(fn, ms));
    for (const [id, t] of Object.entries(SCHEDULE)) {
      at(t.in, () => setShown((s) => new Set(s).add(id)));
      at(t.out, () => setShown((s) => { const n = new Set(s); n.delete(id); return n; }));
    }
    at(Math.max(...Object.values(SCHEDULE).map((t) => t.out)) + 400, onDone);
    return () => { timers.current.forEach(window.clearTimeout); timers.current = []; };
  }, [open, onDone]);

  if (!open) return null;

  return (
    <div className="push-layer">
      {CARDS.map((c) => (
        <button type="button" key={c.id} className={`push${shown.has(c.id) ? ' show' : ''}`}
          aria-hidden={!shown.has(c.id)} tabIndex={shown.has(c.id) ? 0 : -1}
          onClick={() => {
            if (c.id === 'moa') onTapMoa();
            else setShown((s) => { const n = new Set(s); n.delete(c.id); return n; });
          }}>
          <span className="ap" style={c.badgeStyle}>{c.badge}</span>
          <span className="pt">
            <span className="row1"><b>{c.title}</b><span className="ago">지금</span></span>
            <p>{c.body}</p>
          </span>
        </button>
      ))}
    </div>
  );
}
