/**
 * 되묻기 덱 — <b>카드를 좌우로 넘겨 고른다.</b>
 *
 * 프로토타입 온보딩의 `#deck`(포기할 수 없는 소비 / 줄여볼 소비)을 그대로 옮긴 것이다.
 * 생김새(도장·유령 카드·기울기·날아가기)와 규칙(임계값 78px, 최대 8도)까지 같다.
 *
 * <h2>왜 버튼만으로 안 되나</h2>
 *
 * 원본 주석이 이유를 적어 뒀다 — <i>"버튼과 결과가 같다. 드래그는 방향 자체가 의미를 갖는
 * 입력이라 「왼쪽 = 지킨다 / 오른쪽 = 줄인다」를 몸으로 익히게 하는 역할"</i>. 한 장씩
 * 넘기는 몸짓이 있어야 스무 장을 답할 마음이 든다. 버튼도 함께 둔다 — 몸짓만 두면
 * 손이 불편한 사람이 못 쓴다.
 *
 * <h2>여기서 묻는 것</h2>
 *
 * 온보딩은 카테고리를 갈랐지만 이 앱은 <b>결제 한 건</b>을 묻는다.
 * 왼쪽이 '필요했어요', 오른쪽이 '아까워요'다. 모델이 낭비로 본 카드는 테두리가 다르고
 * 그렇게 본 이유가 카드 안에 적힌다.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { api, type PendingVerdict } from '../lib/api';
import { Icon } from './Icons';
import { won, iconOf } from '../lib/format';

/** 이 거리를 넘겨 놓으면 확정 — 원본과 같은 값이다(카드 폭의 약 1/4). */
const THRESH = 78;
/** 최대 기울기(도). */
const MAXROT = 8;

type Pick = 'keep' | 'cut';

const ARROW_L = <svg viewBox="0 0 16 16"><path d="M13 8H3" /><path d="M7 12.5 2.5 8 7 3.5" /></svg>;
const ARROW_R = <svg viewBox="0 0 16 16"><path d="M3 8h10" /><path d="M9 3.5 13.5 8 9 12.5" /></svg>;
const UNDO = <svg viewBox="0 0 14 14"><path d="M2 7a5 5 0 1 0 1.7-3.75" /><path d="M2 2.2V5.4h3.2" /></svg>;

export function SpendDeck({ items, onDone }: { items: PendingVerdict[]; onDone?: () => void }) {
  const [idx, setIdx] = useState(0);
  const [picks, setPicks] = useState<Pick[]>([]);
  const [hintDone, setHintDone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [flying, setFlying] = useState<Pick | null>(null);
  const cardRef = useRef<HTMLDivElement>(null);
  const keepRef = useRef<HTMLSpanElement>(null);
  const cutRef = useRef<HTMLSpanElement>(null);
  const drag = useRef<{ id: number | null; x0: number; dx: number; moved: boolean }>(
    { id: null, x0: 0, dx: 0, moved: false });

  const card = items[idx];
  const left = items.length - idx;
  const showHint = idx === 0 && !hintDone;

  /** 고른 것을 보내고 다음 장으로. 못 보내도 화면은 넘어간다 — 다음에 다시 물으면 된다. */
  const commit = useCallback((kind: Pick) => {
    if (!card) return;
    void api.setWasteVerdict(card.paymentId, kind === 'cut').catch(() => undefined);
    setPicks((p) => [...p.slice(0, idx), kind]);
    setIdx((i) => i + 1);
    setHintDone(true);
  }, [card, idx]);

  useEffect(() => {
    if (idx >= items.length && items.length > 0) onDone?.();
    // 마지막 장을 넘긴 순간에만 알린다.
  }, [idx, items.length, onDone]);

  /** 버튼으로 고르기 — 결과는 드래그와 같고, 카드가 그쪽으로 빠진다. */
  function tap(kind: Pick) {
    if (busy) return;
    setBusy(true);
    setFlying(kind);
    window.setTimeout(() => { setFlying(null); setBusy(false); commit(kind); }, 220);
  }

  function paint() {
    const el = cardRef.current;
    if (!el) return;
    const { dx } = drag.current;
    const r = Math.max(-1, Math.min(1, dx / THRESH));
    el.style.transform = `translateX(${dx}px) rotate(${r * MAXROT}deg)`;
    if (keepRef.current) keepRef.current.style.opacity = String(dx < 0 ? Math.min(1, -dx / THRESH) : 0);
    if (cutRef.current) cutRef.current.style.opacity = String(dx > 0 ? Math.min(1, dx / THRESH) : 0);
    const over = Math.abs(dx) >= THRESH;
    el.classList.toggle('will', over);
    el.classList.toggle('will-keep', over && dx < 0);
    el.classList.toggle('will-cut', over && dx > 0);
  }

  function clearPaint() {
    const el = cardRef.current;
    if (!el) return;
    el.style.transform = '';
    if (keepRef.current) keepRef.current.style.opacity = '0';
    if (cutRef.current) cutRef.current.style.opacity = '0';
    el.classList.remove('will', 'will-keep', 'will-cut');
  }

  function onDown(e: React.PointerEvent<HTMLDivElement>) {
    if (busy || drag.current.id !== null) return;
    drag.current = { id: e.pointerId, x0: e.clientX, dx: 0, moved: false };
    cardRef.current?.setPointerCapture(e.pointerId);
    cardRef.current?.classList.add('drag');
    setHintDone(true);
  }

  function onMove(e: React.PointerEvent<HTMLDivElement>) {
    if (e.pointerId !== drag.current.id) return;
    drag.current.dx = e.clientX - drag.current.x0;
    if (Math.abs(drag.current.dx) > 3) drag.current.moved = true;
    paint();
  }

  function onUp(e: React.PointerEvent<HTMLDivElement>) {
    if (e.pointerId !== drag.current.id) return;
    const el = cardRef.current;
    el?.classList.remove('drag');
    try { el?.releasePointerCapture(e.pointerId); } catch { /* 이미 놓였다 */ }
    const { dx, moved } = drag.current;
    drag.current.id = null;
    if (!moved) { clearPaint(); return; }

    if (Math.abs(dx) >= THRESH && el) {
      // 놓은 자리에서 이어서 날아간다 — 손을 뗀 지점부터 움직여야 이어진 동작으로 보인다.
      const kind: Pick = dx > 0 ? 'cut' : 'keep';
      setBusy(true);
      const dir = kind === 'cut' ? 1 : -1;
      el.style.transition = 'transform .26s cubic-bezier(.2,.8,.2,1), opacity .26s linear';
      el.style.transform = `translateX(${dir * (el.offsetWidth + 140)}px) rotate(${dir * 14}deg)`;
      el.style.opacity = '0';
      window.setTimeout(() => { setBusy(false); commit(kind); }, 240);
      return;
    }
    // 임계값 미달 — 튕김 없이 제자리로.
    el?.classList.add('snap');
    clearPaint();
    window.setTimeout(() => el?.classList.remove('snap'), 300);
  }

  function undo() {
    if (busy || idx === 0) return;
    setIdx((i) => i - 1);
  }

  if (items.length === 0) return null;

  if (!card) {
    const cut = picks.filter((p) => p === 'cut').length;
    return (
      <div className="deck">
        <div className="dk-head"><span className="idx">다 봤어요</span></div>
        <div className="dk-stack">
          <div className="dk-done">
            <div className="dd-row"><span className="k">필요했던 소비</span>
              <span className="v">{picks.length - cut}개</span></div>
            <div className="dd-row cut"><span className="k">새는 돈</span>
              <span className="v">{cut}개</span></div>
          </div>
        </div>
      </div>
    );
  }

  const { icon, bg } = iconOf(card.category2);
  const when = `${Number(card.date.slice(5, 7))}월 ${Number(card.date.slice(8, 10))}일`
    + (card.time ? ` ${card.time}` : '');

  return (
    <div className="deck">
      <div className="dk-head">
        <span className="idx">
          {items.length > 1 ? <><em>{idx + 1}</em> / {items.length}</> : '방금 넣은 소비'}
        </span>
        <button type="button" className="dk-undo" onClick={undo} disabled={idx === 0}>
          {UNDO}이전 선택
        </button>
      </div>

      <div className="dk-stack">
        {/* 뒤에 겹친 카드 — 남은 장수를 몸으로 알린다. */}
        {left > 2 && <div className="dk-ghost g2" />}
        {left > 1 && <div className="dk-ghost g1" />}

        <div
          ref={cardRef}
          key={card.paymentId}
          className={`dk-card${flying === 'keep' ? ' out-keep' : ''}${flying === 'cut' ? ' out-cut' : ''}`
            + `${showHint ? ' nudge' : ''}`}
          style={{ ['--bd' as string]: card.waste ? 'var(--amber)' : 'var(--line)' }}
          onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={onUp}
        >
          <span className="dk-stamp keep" ref={keepRef}>필요했어요</span>
          <span className="dk-stamp cut" ref={cutRef}>새는 돈이었어요</span>
          {/* <b>방향을 말해 준다.</b> "좌우로 넘겨 선택해요"만으로는 어느 쪽이 무엇인지 알 수
              없다. 원본은 아래 버튼의 화살표가 그 몫을 했는데, 카드가 길어 버튼이 화면 밖으로
              밀리는 일이 있다. */}
          {showHint && <div className="dk-hint in">← 필요했어요 · 새는 돈이었어요 →</div>}

          <div className="dk-meta">
            <span className="tg" style={{ background: bg, color: 'var(--t1)' }}>{card.category2}</span>
            <span className="tg time">{when}</span>
            <span className="dk-ic" style={{ background: bg }}><Icon id={icon} /></span>
          </div>
          <div className="dk-nm">{card.merchant}</div>
          <div className="dk-spacer" />
          <div className="dk-avg"><span>금액</span><b>{won(card.amount)}</b></div>
          {/* 모델이 왜 그렇게 봤는지 — 근거 없이 물으면 답할 수가 없다. */}
          <div className="dk-why">
            {card.waste ? (card.reason ?? '평소보다 많이 썼어요.') : '평소와 비슷한 소비예요.'}
          </div>
        </div>
      </div>

      <div className="dk-btns">
        <button type="button" className="dk-keep" onClick={() => tap('keep')}>{ARROW_L}필요했어요</button>
        <button type="button" className="dk-cut" onClick={() => tap('cut')}>새는 돈이었어요{ARROW_R}</button>
      </div>
    </div>
  );
}
