/**
 * ON-01 가치 소개 — 가로로 넘기는 3장 (프로토타입_0806 `s-walk`).
 *
 * <b>스크롤이 정본이고 점·버튼은 따라간다.</b> 위치를 상태로 들고 버튼으로만 옮기면 손가락으로
 * 쓸어 넘겼을 때 점이 안 따라온다. 실제로 넘어간 자리(`scrollLeft`)를 읽어 점과 버튼을 맞춘다 —
 * 스크롤 스냅이 어디서 멈출지는 브라우저가 정하므로, 그 결과를 받아 쓰는 편이 어긋나지 않는다.
 *
 * <b>건너뛰기를 마지막 장에서 감춘다.</b> 마지막에서는 '시작하기'가 곧 건너뛰기라 둘이 같은 일을
 * 한다. 같은 자리에 같은 뜻의 버튼이 둘이면 어느 쪽이 무엇인지 생각하게 만든다.
 */
import { useRef, useState } from 'react';
import { Icon } from '../components/Icons';
import { ErrorBox } from '../components/ui';
import { useSession } from '../state/session';
import { api } from '../lib/api';
import { DEMO_CI, DEMO_ENABLED } from '../lib/config';

interface Slide {
  icon?: string;
  title: string;
  sub: React.ReactNode;
  /** 마지막 장은 아이콘이 아니라 방 그림이다 — 보상이 무엇인지 글보다 그림이 빠르다. */
  art?: React.ReactNode;
}

const SLIDES: Slide[] = [
  {
    icon: 'i-chart',
    title: '흩어진 소비를 한눈에',
    sub: <>계좌와 카드를 연결하면<br />MOA가 소비 패턴을 분석해요</>,
  },
  {
    icon: 'i-shield',
    title: '미션으로 가볍게 줄여요',
    sub: <>일주일 미션을 지켜내면<br />포인트가 쌓여요</>,
  },
  {
    title: '지켜낸 하루가 방을 채워요',
    sub: <>무지출 하루마다 냥지킴이 방에<br />새 소품이 도착해요</>,
    art: (
      <>
        <img src="/room/catsit.png" width={76} alt="" />
        <img src="/room/mood.png" width={36} style={{ marginLeft: 8 }} alt="" />
      </>
    ),
  },
];

export function Walk() {
  const { go, setUserId, setLinked } = useSession();
  const track = useRef<HTMLDivElement>(null);
  const [idx, setIdx] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const last = SLIDES.length - 1;

  /**
   * 개발용 — 본인인증을 건너뛰고 생성 마이데이터 CI로 바로 연결한다.
   *
   * 프로토타입에는 없는 버튼이다. 예전에는 스플래시에 있었는데 그 화면이 브랜드 면으로 바뀌어
   * 둘 곳이 없어졌다. 인증 직전 화면인 여기가 맥락이 가장 가깝다 — `DEMO_ENABLED` 일 때만 뜬다.
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
    const i = Math.round(t.scrollLeft / t.clientWidth);
    if (i !== idx) setIdx(Math.max(0, Math.min(last, i)));
  }

  function slideTo(i: number) {
    const t = track.current;
    if (!t) return;
    t.scrollTo({ left: i * t.clientWidth, behavior: 'smooth' });
  }

  return (
    <section className="screen" style={{ background: 'var(--card)' }}>
      <div className="walk-top">
        {/* 첫 장에서는 뒤로 갈 곳이 없다. 자리는 남겨 둔다 — 버튼이 사라지면 건너뛰기가 움직인다. */}
        <button type="button" className="back" onClick={() => slideTo(idx - 1)}
          style={{ visibility: idx > 0 ? 'visible' : 'hidden' }} aria-label="이전 장">‹</button>
        <button type="button" className="walk-skip" onClick={() => go('auth')}
          style={{ visibility: idx === last ? 'hidden' : 'visible' }}>건너뛰기</button>
      </div>

      <div className="walk-track" ref={track} onScroll={sync}>
        {SLIDES.map((s, i) => (
          <div className="walk-slide" key={s.title} aria-hidden={i !== idx}>
            <div className="walk-art">
              {s.art ?? <Icon id={s.icon!} size={64} />}
            </div>
            <div className="walk-t">{s.title}</div>
            <div className="walk-s">{s.sub}</div>
          </div>
        ))}
      </div>

      <div className="walk-dots" aria-hidden="true">
        {SLIDES.map((s, i) => <i key={s.title} className={i === idx ? 'on' : undefined} />)}
      </div>

      <div className="pad" style={{ paddingBottom: 40 }}>
        <ErrorBox error={error} />
        <button type="button" className="btn btn-primary" disabled={busy}
          onClick={() => (idx >= last ? go('auth') : slideTo(idx + 1))}>
          {idx === last ? '시작하기' : '다음'}
        </button>

        {DEMO_ENABLED && (
          <p style={{ textAlign: 'center', margin: '18px 0 0' }}>
            <button type="button" className="btn btn-ghost btn-sm" disabled={busy}
              onClick={() => void skipWithDemoCi()}
              title="개발용 — 본인인증을 건너뛰고 생성 마이데이터로 바로 연결">
              {busy ? '연결 중…' : '🧪 개발용 건너뛰기 (인증 없이 연결)'}
            </button>
          </p>
        )}
      </div>
    </section>
  );
}
