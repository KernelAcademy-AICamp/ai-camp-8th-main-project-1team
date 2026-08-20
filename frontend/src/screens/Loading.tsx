/**
 * MD-04 조회 진행 + 분석 로딩. 마운트하면 실제 소비 분석(`/api/analysis`)을 부르고,
 * 응답과 연출이 모두 끝나면 이번 달 정하기(ob1)로 넘어간다.
 *
 * 연출만 보고 넘어가지 않는다 — 분석이 늦으면 화면도 기다린다. 대신 실패했을 때는
 * 사용자가 직접 넘어가거나 다시 시도할 수 있어야 한다(KWCAG 2.2.1 응답시간 조절).
 */
import { useEffect, useRef, useState } from 'react';
import { Screen, ErrorBox } from '../components/ui';
import { DocLoad } from '../components/DocLoad';
import { useSession } from '../state/session';
import { api } from '../lib/api';

const STEPS = [
  ['카드 내역을 불러오는 중…', '잠시만요, 그동안의 소비를 훑어보고 있어요'],
  ['소비 패턴을 분석하는 중…', '언제 얼마를 쓰는지 살펴보고 있어요'],
  ['줄일 수 있는 지출을 찾는 중…', '무리 없이 지킬 수 있는 목표를 계산해요'],
  ['거의 다 됐어요…', '당신만의 절약 챌린지를 준비 중이에요'],
];

export function Loading() {
  const { userId, replace, setAnalysis } = useSession();
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
    // **여기서 미분류를 한 번 채운다.** 온보딩 전 과정에서 `/unclassified` 를 부르는 곳이
    // 여기밖에 없다 — 안 부르면 새 사용자는 모르는 가맹점이 전부 '카테고리없음'인 채로
    // 온보딩을 마치고, 마이 > 분류 정리를 스스로 찾아 들어가야만 채워진다.
    // 이 화면은 이미 "소비 패턴을 분석하는 중…"으로 기다리는 자리라 몇 초가 자연스럽다.
    //
    // **실패해도 온보딩을 막지 않는다.** AI 추정은 있으면 좋은 것이지 없으면 못 가는 것이
    // 아니다 — 확정 분류(사전)는 이미 붙어 있고, 추정이 없으면 미분류로 보일 뿐이다.
    //
    // **둘을 같이 출발시킨다.** 예전에는 `unclassified` 를 먼저 기다리고 그 결과를 버린 뒤
    // `analysis` 를 불렀다 — 앞 결과를 쓰지도 않는데 **가장 느린 문을 맨 앞에 세운 직렬**이었다.
    // `unclassified` 는 켜져 있으면 미분류 한 곳당 무료 6~10초를 쓴다(`MerchantAskService`).
    // 그 시간이 분석 시간에 그대로 더해졌다. 나란히 부르면 둘 중 느린 쪽만큼만 걸린다.
    Promise.all([
      api.unclassified(userId).catch(() => null),
      api.analysis(userId),
    ])
      .then(([, a]) => { if (alive) { setAnalysis(a); setReady(true); } })
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

  /**
   * 분석과 연출이 모두 끝나면 넘어간다. **이력에 남기지 않는다**(`replace`) — 로딩은 지나가는
   * 화면이라 되돌아올 자리가 아니고, 남겨 두면 ob1 에서 뒤로 누른 사람이 여기 도착해
   * <b>분석 API 를 처음부터 다시 부르고</b> 몇 초 뒤 다시 ob1 으로 밀린다
   * (`moved` ref 는 재마운트에 리셋된다).
   */
  useEffect(() => {
    if (ready && played && !moved.current) { moved.current = true; replace('ob'); }
  }, [ready, played, replace]);

  const progress = error ? 1 : ((i + 1) / STEPS.length);

  return (
    <Screen id="loading" title="소비 분석 중">
      <div className="loadwrap">
        {/* 오류일 때는 훑는 시늉을 멈춘다 — 안 읽고 있는데 읽는 그림을 보이면 거짓말이다. */}
        {!error && <DocLoad />}
        {error ? (
          <div style={{ width: '100%', maxWidth: 360 }}>
            <p className="load-title">분석을 마치지 못했어요</p>
            <ErrorBox error={error} />
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <button type="button" className="btn btn-primary" onClick={() => { setError(null); setTick((t) => t + 1); }}>
                다시 시도
              </button>
              {/* 여기도 `replace` 다 — 분석에 실패한 화면으로 뒤로 돌아올 이유가 없고,
                  돌아오면 실패한 요청을 다시 던지게 된다. */}
              <button type="button" className="btn btn-ghost" onClick={() => replace('ob')}>그냥 진행</button>
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
