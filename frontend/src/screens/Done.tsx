/**
 * ON-03 온보딩 완료·첫 챕터. 서버가 만들어준 챌린지 값으로 응원 문구를 띄운다.
 *
 * 자동으로 홈에 넘어가는 화면이므로 **시간 제한이 있는 콘텐츠**다. 그래서 세 가지를 함께 둔다
 * (KWCAG 2.2 검사항목 14 응답시간 조절 · KS X 3253 6.3):
 *   ① 남은 시간을 미리 알린다  ② 지금 넘어가는 버튼  ③ 자동 전환을 멈추는 버튼
 * 셋 중 하나라도 빠지면 화면을 다 읽기 전에 넘어가 버리는 사용자가 생긴다.
 */
import { useEffect, useRef, useState } from 'react';
import { Orb, Screen } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { won } from '../lib/format';

const AUTO_SECONDS = 5;

export function Done() {
  const { go } = useSession();
  const { home } = useGuardian();
  const [left, setLeft] = useState(AUTO_SECONDS);
  const [paused, setPaused] = useState(false);
  const moved = useRef(false);

  const goHome = () => { moved.current = true; go('home'); };

  // 이동은 effect에서 한다 — setState 갱신 함수 안에서 화면을 옮기면
  // StrictMode가 갱신 함수를 두 번 부를 때 이동도 두 번 일어난다.
  useEffect(() => {
    if (paused) return;
    if (left <= 0) {
      if (!moved.current) { moved.current = true; go('home'); }
      return;
    }
    const t = window.setTimeout(() => setLeft((n) => n - 1), 1000);
    return () => window.clearTimeout(t);
  }, [paused, left, go]);

  const target = home?.challenge.targetSaving ?? 0;
  const days = home?.challenge.daysTotal ?? 30;
  const label = home?.challenge.categoryLabel;

  return (
    <Screen title="챌린지 시작" background="linear-gradient(160deg,#EAF2FF,#F2F4F6)">
      <div className="done-hero">
        <Orb size={84} bob />
        <p style={{ fontSize: 23, fontWeight: 800, margin: 0 }}>{days}일의 길이 시작됐어요</p>
        <p style={{ fontSize: 15, color: 'var(--t2)', lineHeight: 1.6, margin: 0 }}>
          {target > 0 ? (
            <>이번 챌린지 <b style={{ color: 'var(--blue-t)' }}>{won(target)}</b>, 지킴이와 함께 지켜봐요.<br /></>
          ) : null}
          {label && <>{label}부터 같이 볼게요 · </>}무리 안 하게 옆에서 챙길게요 💪
        </p>
      </div>

      <div className="pad" style={{ paddingBottom: 40 }}>
        <p className="empty" style={{ textAlign: 'center' }} role="status">
          {paused ? '자동 이동을 멈췄어요. 준비되면 아래 버튼을 눌러주세요.' : `${left}초 뒤 홈으로 이동해요.`}
        </p>
        <button type="button" className="btn btn-primary" onClick={goHome}>홈으로</button>
        {!paused && (
          <button type="button" className="btn btn-ghost btn-sm" style={{ width: '100%', marginTop: 8 }}
            onClick={() => setPaused(true)}>
            자동 이동 멈추기
          </button>
        )}
      </div>
    </Screen>
  );
}
