/**
 * ON-00 부팅 — 브랜드 화면 2초 뒤 가치 소개로 넘어간다 (프로토타입_0806 `s-boot`).
 *
 * <b>왜 화면을 쪼갰나.</b> 예전 스플래시는 로고와 시작 버튼을 한 화면에 두고 바로 인증으로
 * 넘겼다. 처음 온 사람은 무엇을 하는 앱인지 모른 채 주민등록번호를 요구받는다. 이제
 * 브랜드를 먼저 보이고({@link Boot}) 무엇을 해 주는지 말한 뒤({@link Walk}) 인증을 청한다.
 *
 * <b>자동 전환에도 빠져나갈 문을 둔다.</b> 2초를 기다리지 못하는 사람이 화면을 누르면 바로
 * 넘어간다 — 자동 전환만 있으면 기다리는 것 말고 할 수 있는 일이 없다.
 */
import { useEffect, useRef, useState } from 'react';
import { useSession } from '../state/session';

/** 브랜드 노출 시간. 프로토타입의 2초 + 사라지는 0.45초. */
const HOLD_MS = 2000;
const FADE_MS = 450;

export function Boot() {
  const { replace } = useSession();
  const [leaving, setLeaving] = useState(false);
  /** 눌러서 넘어갔는데 타이머가 또 넘기는 것을 막는다. */
  const done = useRef(false);

  useEffect(() => {
    const hold = setTimeout(() => leave(), HOLD_MS);
    return () => clearTimeout(hold);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /**
   * 스플래시를 빠져나간다. **이력에 남기지 않는다**(`replace`) — 브랜드 화면은 사람이 돌아올
   * 자리가 아니고, 남겨 두면 뒤로 눌러 여기 도착한 순간 위 타이머가 다시 돌아 2.45초 뒤
   * 앞으로 되밀린다. `done` ref 가 그 이중 실행을 막지만 <b>뒤로가기는 재마운트라 ref 가
   * 리셋된다</b> — 가드가 무력한 자리다.
   */
  function leave() {
    if (done.current) return;
    done.current = true;
    setLeaving(true);
    setTimeout(() => replace('walk'), FADE_MS);
  }

  return (
    // 브랜드 면이라 화면 전체가 초록이다. Screen 을 쓰지 않는 유일한 화면 —
    // 앱바도 탭바도 없고 배경이 본문 색과 다르다.
    <section id="s-boot" className={`screen boot${leaving ? ' out' : ''}`} onClick={leave}
      aria-label="MOA 시작 화면">
      <div className="boot-wrap">
        <div className="boot-cap">내 소비를 지켜주는</div>
        <div className="boot-logo">MOA</div>
      </div>
    </section>
  );
}
