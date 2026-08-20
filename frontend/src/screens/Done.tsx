/**
 * 온보딩 완료 (프로토타입_0818 `s-obdone`).
 *
 * <p><b>0818 개편으로 조용해졌다.</b> 예전에는 지킴이 오브·목표 금액·자동 이동 카운트다운이
 * 함께 있었다. 지금은 <b>체크 하나와 두 줄</b>이다 — 방금 다섯 걸음을 밟고 온 사람에게
 * 읽을 것을 더 주는 대신 "끝났다"만 분명히 말한다.
 *
 * <p><b>자동 이동을 없앴다</b>(디자인). 그래서 시간 제한 콘텐츠가 아니게 되었고,
 * KWCAG 2.2 응답시간 조절의 대상에서도 벗어난다 — 예전에 남은 시간·멈춤 버튼을 함께 두던
 * 이유가 자동 전환이었는데, 그 전환 자체가 사라졌다. 홈으로 가는 것은 사람이 정한다.
 *
 * <p>연출은 세 박자다: 원이 튀어 오르고(.5초, 살짝 넘겼다 돌아오는 곡선) → 체크가 그려지고
 * (.35초) → 글이 차례로 떠오른다(.35초·.5초 지연). <b>순서가 곧 문장</b>이라 한꺼번에
 * 띄우지 않는다.
 */
import { useEffect, useState } from 'react';
import { Screen, Cta } from '../components/ui';
import { useSession } from '../state/session';

export function Done() {
  const { replace } = useSession();
  /** 마운트 직후 한 박자 뒤에 연출을 켠다 — 첫 페인트에 이미 켜져 있으면 전환이 안 보인다. */
  const [shown, setShown] = useState(false);
  useEffect(() => {
    const t = window.setTimeout(() => setShown(true), 60);
    return () => window.clearTimeout(t);
  }, []);

  return (
    <Screen id="obdone" title="첫 챌린지가 시작됐어요">
      <div className="scroll" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <div className={`od-wrap${shown ? ' in' : ''}`}>
          <div className="od-check" aria-hidden="true">
            <svg viewBox="0 0 96 96">
              <circle cx="48" cy="48" r="44" fill="var(--blue)" />
              {/* 획 길이만큼 점선 간격을 주고 오프셋을 0으로 옮겨 '그려지는' 것처럼 보이게 한다. */}
              <path className="tick" d="M30 49 L43 62 L67 37" stroke="#fff" strokeWidth="7"
                fill="none" strokeLinecap="round" strokeLinejoin="round"
                strokeDasharray="60" strokeDashoffset="60" />
            </svg>
          </div>
          <h3>첫 챌린지가 시작됐어요</h3>
          <p>지킴이와 하루씩 지켜가요</p>
        </div>
      </div>
      <Cta>
        {/* `replace` 다 — 완료 화면은 되돌아올 자리가 아니다. 남겨 두면 홈에서 뒤로 눌렀을 때
            이미 끝난 축하가 다시 뜬다(`state/session.tsx` 의 `replace` 주석). */}
        <button type="button" className="btn btn-primary" onClick={() => replace('home')}>
          홈으로 가기
        </button>
      </Cta>
    </Screen>
  );
}
