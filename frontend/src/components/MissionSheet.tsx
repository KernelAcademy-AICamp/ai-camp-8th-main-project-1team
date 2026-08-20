/**
 * 다음 주 미션 고르기 시트 (프로토타입_0806 `#msSheet`).
 *
 * <b>미래 시제는 이 시트 안에만 있다.</b> 마이룸의 나머지는 "지금 어떤가"를 말하는데, 여기만
 * "다음 주에 무엇을 할까"를 묻는다. 진행 중인 이번 주 미션과 섞이면 무엇이 이미 걸린 조건이고
 * 무엇이 아직 고르는 중인지 알 수 없어, 고르는 일은 시트 안으로 뺐다.
 *
 * <b>라디오 하나로 고른다.</b> 여러 개를 담게 하면 포인트 몫이 쪼개져 각 미션이 10P가 되고,
 * 그러면 "셋 다 해야 30P"가 되어 고른 게 아니라 늘어난 것이 된다.
 *
 * <b>왜 권하는지를 함께 적는다.</b> "금 19~22시 배달 안 쓰기" 옆에 "금요일 그 시간에 4번
 * 쓰셨어요"가 없으면, 지키래서 지키는 숙제가 된다.
 */
import { useEffect, useState } from 'react';
import { Icon } from './Icons';
import type { MissionCandidate } from '../lib/api';

/** 미션 종류 → 아이콘·바탕색. 카테고리 이름이 아니라 <b>조건 유형</b>으로 가른다(원칙 4). */
const KIND: Record<string, { icon: string; bg: string }> = {
  MAX_COUNT: { icon: 'i-card', bg: 'var(--blue-weak)' },
  AVOID_SLOT: { icon: 'i-bell', bg: 'var(--c-cafe)' },
  NO_SPEND_STREAK_MIN: { icon: 'i-flame', bg: 'var(--c-shop)' },
  LABELING_COUNT_MIN: { icon: 'i-doc', bg: 'var(--c-cvs)' },
};
const kindOf = (t: string) => KIND[t] ?? { icon: 'i-shield', bg: 'var(--bg)' };

export function MissionSheet({ open, candidates, picked, reward, onClose, onConfirm }: {
  open: boolean;
  candidates: MissionCandidate[];
  /** 이미 담아 둔 미션의 key. 없으면 null. */
  picked: string | null;
  /** 성공하면 받을 포인트. */
  reward: number;
  onClose: () => void;
  onConfirm: (key: string) => Promise<void> | void;
}) {
  const [sel, setSel] = useState<string | null>(picked);
  const [busy, setBusy] = useState(false);

  // 열 때마다 지금 담아 둔 것으로 되돌린다 — 지난번에 고르다 만 것이 남아 있으면
  // 무엇이 실제로 담긴 것인지 헷갈린다.
  useEffect(() => { if (open) { setSel(picked); setBusy(false); } }, [open, picked]);

  return (
    <>
      <div className={`tp-dim${open ? ' show' : ''}`} onClick={onClose} aria-hidden="true" />
      <div className={`tp-sheet${open ? ' show' : ''}`} role="dialog" aria-label="다음 주 미션 고르기"
        aria-hidden={!open}>
        <div className="tp-head" style={{ marginBottom: 4 }}>다음 주 미션 고르기</div>
        <div className="tp-cap">
          성공한 미션은 이어지고, 일요일 정산 때 +{reward}P를 받아요
        </div>

        {candidates.length === 0 ? (
          <p className="empty">
            아직 권할 만한 미션이 없어요. 소비가 조금 더 쌓이면 무엇을 줄일 수 있는지 보여요.
          </p>
        ) : candidates.map((c) => (
          <button type="button" key={c.key} className={`ms-row${sel === c.key ? ' on' : ''}`}
            aria-pressed={sel === c.key} onClick={() => setSel(c.key)}>
            <span className="mic" style={{ background: kindOf(c.type).bg }}>
              <Icon id={kindOf(c.type).icon} className="ci" />
            </span>
            <span className="mtx"><b>{c.text}</b><span>{c.why}</span></span>
            <span className="ms-radio" aria-hidden="true"><Icon id="i-check" /></span>
          </button>
        ))}

        <button type="button" className="btn btn-primary"
          /* 0818: 버튼 높이를 --cta-h 로 통일했다 — 인라인 padding·font-size 를 걷어낸다. */
          style={{ marginTop: 20 }}
          disabled={sel === null || busy}
          onClick={async () => {
            if (sel === null) return;
            setBusy(true);
            try { await onConfirm(sel); } finally { setBusy(false); }
          }}>
          {busy ? '담는 중…' : '이 미션으로 할게요'}
        </button>
      </div>
    </>
  );
}
