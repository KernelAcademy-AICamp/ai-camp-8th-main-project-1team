/**
 * MD-04 조회 진행 + 분석 로딩. 마운트하면 실제 소비 분석(`/api/analysis`)을 부르고,
 * 응답과 연출이 모두 끝나면 이번 달 정하기(ob1)로 넘어간다.
 *
 * 연출만 보고 넘어가지 않는다 — 분석이 늦으면 화면도 기다린다. 대신 실패했을 때는
 * 사용자가 직접 넘어가거나 다시 시도할 수 있어야 한다(KWCAG 2.2.1 응답시간 조절).
 */
import { useEffect, useRef, useState } from 'react';
import { Orb, Screen, ErrorBox } from '../components/ui';
import { useSession } from '../state/session';
import { api } from '../lib/api';

const STEPS = [
  ['카드 내역을 불러오는 중…', '잠시만요, 그동안의 소비를 훑어보고 있어요'],
  ['소비 패턴을 분석하는 중…', '언제 얼마를 쓰는지 살펴보고 있어요'],
  ['줄일 수 있는 지출을 찾는 중…', '무리 없이 지킬 수 있는 목표를 계산해요'],
  ['거의 다 됐어요…', '당신만의 절약 챌린지를 준비 중이에요'],
];

export function Loading() {
  const { userId, go, setAnalysis } = useSession();
  const [i, setI] = useState(0);
  const [error, setError] = useState<unknown>(null);
  const [ready, setReady] = useState(false);      // 분석 응답 도착
  const [played, setPlayed] = useState(false);    // 연출 종료
  const [tick, setTick] = useState(0);
  const moved = useRef(false);

  // 분석 로드 — StrictMode 이중 실행에도 안전하도록 취소 플래그만 둔다.
  useEffect(() => {
    let alive = true;
    setError(null);
    setReady(false);
    api.analysis(userId)
      .then((a) => { if (alive) { setAnalysis(a); setReady(true); } })
      .catch((e) => { if (alive) setError(e); });
    return () => { alive = false; };
  }, [userId, setAnalysis, tick]);

  // 진행 연출
  useEffect(() => {
    const timers: number[] = [];
    STEPS.forEach((_, idx) => {
      if (idx > 0) timers.push(window.setTimeout(() => setI(idx), idx * 900));
    });
    timers.push(window.setTimeout(() => setPlayed(true), STEPS.length * 900));
    return () => timers.forEach(clearTimeout);
  }, []);

  useEffect(() => {
    if (ready && played && !moved.current) { moved.current = true; go('ob1'); }
  }, [ready, played, go]);

  const progress = error ? 1 : ((i + 1) / STEPS.length);

  return (
    <Screen title="소비 분석 중">
      <div className="loadwrap">
        <Orb size={72} bob={!error} />
        {error ? (
          <div style={{ width: '100%', maxWidth: 360 }}>
            <p className="load-title">분석을 마치지 못했어요</p>
            <ErrorBox error={error} />
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <button type="button" className="btn btn-primary" onClick={() => { setError(null); setTick((t) => t + 1); }}>
                다시 시도
              </button>
              <button type="button" className="btn btn-ghost" onClick={() => go('ob1')}>그냥 진행</button>
            </div>
          </div>
        ) : (
          <>
            <p className="load-title" aria-live="polite">{STEPS[i][0]}</p>
            <div className="load-bar" role="progressbar" aria-valuenow={Math.round(progress * 100)}
              aria-valuemin={0} aria-valuemax={100} aria-label="분석 진행률">
              <i style={{ width: `${progress * 100}%` }} />
            </div>
            <p className="load-step">{STEPS[i][1]}</p>
          </>
        )}
      </div>
    </Screen>
  );
}
