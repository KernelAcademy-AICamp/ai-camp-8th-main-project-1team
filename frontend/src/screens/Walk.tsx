/**
 * ON-01 워크스루 — 가로로 넘기는 3장 (프로토타입_0818 `s-walk`).
 *
 * <b>0818 개편으로 통째로 바뀌었다.</b> 예전에는 아이콘 + 제목 + 부제의 흐름 배치였고 위에
 * 뒤로·건너뛰기가 있었다. 지금은 <b>그림이 주인공</b>이고 글은 한 덩어리이며, 위쪽 버튼 줄이
 * 사라지고 점과 CTA 만 남았다. 장마다 그림이 다르고 <b>모션도 다르다</b> —
 *   1장 날개 달린 돈주머니: 위아래 부유(3.0초) × 좌우 회전(6.0초)
 *   2장 푸시 알림 목업:     위에서 카드가 내려와 살짝 튕기며 안착(0.68초, 1회)
 *   3장 허들 넘는 돼지:      도약(2.6초) × 기울기(5.2초), 허들은 고정
 *
 * <b>왜 주기를 두 축으로 어긋나게 두나.</b> 한 주기로 겹치면 같은 자세가 반복돼 루프가 눈에
 * 띈다. 3초와 6초처럼 배수로 어긋나게 두면 같은 자세가 잘 안 돌아온다 — 프로토타입 주석이
 * 그것을 "루프는 주기가 2배로 어긋나는 두 축을 겹친다"로 적어 뒀다.
 *
 * <b>스크롤이 정본이고 점·버튼은 따라간다.</b> 위치를 상태로 들고 버튼으로만 옮기면 손가락으로
 * 쓸어 넘겼을 때 점이 안 따라온다. 실제로 멈춘 자리(`scrollLeft`)를 읽어 맞춘다.
 *
 * <b>등장 연출은 장마다 한 번만.</b> 프로토타입의 `seen` 클래스와 같다 — 되돌아왔을 때 글이
 * 다시 떠오르면 읽던 사람을 방해한다.
 */
import { useEffect, useRef, useState } from 'react';
import { ErrorBox } from '../components/ui';
import { useSession } from '../state/session';
import { api } from '../lib/api';
import { DEMO_CI, DEMO_ENABLED } from '../lib/config';

/** 장 수. 점·버튼·스크롤이 같은 값을 봐야 어긋나지 않는다. */
const SLIDES = 3;

export function Walk() {
  const { go, setUserId, setLinked } = useSession();
  const track = useRef<HTMLDivElement>(null);
  const [idx, setIdx] = useState(0);
  /** 등장 연출을 이미 재생한 장. 되돌아와도 다시 떠오르지 않게 한다(프로토타입 `seen`). */
  const [seen, setSeen] = useState<Set<number>>(() => new Set([0]));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  /** 화면 자체의 등장(점·버튼)은 마운트 직후 한 번(프로토타입 `wshow`). */
  const [shown, setShown] = useState(false);
  const last = SLIDES - 1;

  useEffect(() => {
    const t = window.setTimeout(() => setShown(true), 30);
    return () => window.clearTimeout(t);
  }, []);

  /**
   * 개발용 — 본인인증을 건너뛰고 생성 마이데이터 CI로 바로 연결한다.
   * 프로토타입에는 없는 버튼이라 `DEMO_ENABLED` 일 때만 뜬다.
   */
  async function skipWithDemoCi() {
    setBusy(true); setError(null);
    try {
      const companies = await api.mydataCompanies();
      const r = await api.linkSynthetic(DEMO_CI, companies.map((c) => c.id));
      setUserId(r.userId);
      setLinked(true);
      go('loading');
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  }

  /** 실제로 멈춘 자리를 읽어 점·버튼을 맞춘다. */
  function sync() {
    const t = track.current;
    if (!t || t.clientWidth === 0) return;
    const i = Math.max(0, Math.min(last, Math.round(t.scrollLeft / t.clientWidth)));
    if (i === idx) return;
    setIdx(i);
    setSeen((prev) => (prev.has(i) ? prev : new Set(prev).add(i)));
  }

  function next() {
    if (idx >= last) { go('auth'); return; }
    const t = track.current;
    t?.scrollTo({ left: (idx + 1) * t.clientWidth, behavior: 'smooth' });
  }

  const slideClass = (i: number) => `walk-slide${seen.has(i) ? ' seen' : ''}`;

  return (
    <section id="s-walk" className={`screen walk${shown ? ' wshow' : ''}`}
      style={{ background: '#fff', paddingTop: 0 }} aria-label="MOA 소개">
      <div className="walk-track" ref={track} onScroll={sync}>

        {/* 1/3 — 날개 달린 돈주머니. 부유와 회전을 겹쳐 같은 자세가 반복되지 않게 한다. */}
        <div className={slideClass(0)} aria-hidden={idx !== 0}>
          <div className="walk-t">매달 어디로 갔는지 모르는 돈,<br />MOA가 찾아드릴게요</div>
          <div className="walk-art w1"
            style={{ left: 'calc(50% - 7.5px)', top: 328, width: 198, height: 198 }}>
            <span className="w-fly">
              <img src="/walk/moneybag.png" width={198} height={198} alt="" draggable={false} />
            </span>
          </div>
        </div>

        {/* 2/3 — 푸시 알림 목업. 이미지가 아니라 **레이어로 그린다**(프로토타입 실측 좌표).
            그림으로 넣으면 글자가 비트맵이 되어 확대·다크모드·번역에서 깨진다. */}
        <div className={slideClass(1)} aria-hidden={idx !== 1}>
          <div className="walk-t">계좌와 카드를 연결하면<br />MOA가 소비 패턴을 분석해요</div>
          <div className="walk-art wp"
            style={{ left: 'calc(50% + 0.5px)', top: 327, width: 320, height: 221 }}>
            {/* 폰 프레임 두 겹(바깥 연회색·안쪽 실선)과 스피커 — 순수 CSS 도형이다. */}
            <i className="wp-f1" aria-hidden="true" />
            <i className="wp-f2" aria-hidden="true" />
            <i className="wp-notch" aria-hidden="true" />
            <div className="wp-card">
              <i className="wp-ic" aria-hidden="true" />
              <span className="wp-ictx" aria-hidden="true">MOA</span>
              <b className="wp-app">MOA</b>
              <span className="wp-ago">지금</span>
              <p className="wp-body">
                이번 달 지킨 돈이 44,500원을 넘었어요.<br />
                저번 달보다 17,800원이나 더 아꼈어요!
              </p>
            </div>
          </div>
        </div>

        {/* 3/3 — 허들 넘는 돼지 저금통. 원본 한 장을 색 경계에서 둘로 갈라, 돼지만 뛰고
            허들은 제자리에 있게 한다(프로토타입: 재합성하면 원본과 픽셀 차이 0). */}
        <div className={slideClass(2)} aria-hidden={idx !== 2}>
          <div className="walk-t" style={{ right: 'auto', whiteSpace: 'nowrap' }}>
            매달 결산으로 습관을 확인하고<br />지킬 수 있는 목표로 다시 시작해요
          </div>
          <div className="walk-art w3"
            style={{ left: 'calc(50% + 0.5px)', top: 278, width: 295, height: 301 }}>
            <img className="w3-hur" src="/walk/hurdle.png" width={295} height={301}
              alt="" draggable={false} />
            <span className="w3-pig">
              <img src="/walk/pig.png" width={295} height={301} alt="" draggable={false} />
            </span>
          </div>
        </div>
      </div>

      <div className="walk-dots" aria-hidden="true">
        {Array.from({ length: SLIDES }, (_, i) => (
          <i key={i} className={i === idx ? 'on' : undefined} />
        ))}
      </div>

      <button type="button" className="btn btn-primary walk-cta" disabled={busy} onClick={next}>
        {idx === last ? '시작하기' : '다음'}
      </button>

      {/* 개발용 통로와 오류 자리 — 프로토타입에 없으므로 CTA 위에 조용히 둔다. */}
      {(error != null || DEMO_ENABLED) && (
        <div className="walk-dev">
          <ErrorBox error={error} />
          {DEMO_ENABLED && (
            <button type="button" className="btn btn-ghost btn-sm" disabled={busy}
              onClick={() => void skipWithDemoCi()}
              title="개발용 — 본인인증을 건너뛰고 생성 마이데이터로 바로 연결">
              {busy ? '연결 중…' : '🧪 개발용 건너뛰기 (인증 없이 연결)'}
            </button>
          )}
        </div>
      )}
    </section>
  );
}
