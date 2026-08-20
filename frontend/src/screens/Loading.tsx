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
    // **기다리지 않는다.** 위 설명대로 이 화면이 미분류를 한 번 채우는 것은 맞지만,
    // 그 결과를 여기서 쓰지는 않는다(`then` 의 첫 값을 버리고 있었다). 그런데 `Promise.all`
    // 이라 **가장 느린 쪽을 기다렸다** — 실 명세서에는 업종코드가 없어 새 사용자는 미분류가
    // 수십~수백 곳이고, 운영 실측으로 그 한 번이 **39초**였다(2026-08-20 userId=33).
    // `NarrativeCacheService` 규율대로 올려만 두고 화면은 분석만 기다린다. 채워진 분류는
    // 다음 화면 진입에서 보인다.
    void api.unclassified(userId).catch(() => null);
    api.analysis(userId)
      .then((a) => { if (alive) { setAnalysis(a); setReady(true); } })
      .catch((e) => { if (alive) setError(e); });
    return () => { alive = false; };
  }, [userId, setAnalysis, tick]);

  /**
   * <b>진행 바는 실제 진행을 따라간다.</b>
   *
   * <p>예전에는 `900ms × 단계` 로 그냥 흘러갔다. 분석이 40초 걸려도 바는 3.6초 만에 끝까지
   * 가서, 사용자는 <b>다 됐는데 안 넘어가는 화면</b>을 30초 넘게 봤다(사용자 보고 2026-08-20).
   * 진행 바가 실제와 무관하면 그것은 정보가 아니라 거짓말이다.
   *
   * <p>그렇다고 분석의 진짜 퍼센트를 알 수는 없다(서버가 안 알려 준다). 대신 <b>마지막 칸을
   * 비워 둔다</b> — 응답이 오기 전에는 아무리 오래 걸려도 마지막 단계로 넘어가지 않고,
   * 오면 곧바로 채운다. "거의 다 됐어요"가 실제로 거의 다 된 것을 뜻하게 된다.
   */
  useEffect(() => {
    const last = STEPS.length - 1;
    const timers: number[] = [];
    for (let idx = 1; idx < last; idx++) {
      timers.push(window.setTimeout(() => setI(idx), idx * 900));
    }
    return () => timers.forEach(clearTimeout);
  }, [tick]);

  /** 응답이 오면 마지막 칸을 채우고, 짧게 보여 준 뒤 넘어간다. */
  useEffect(() => {
    if (!ready) return;
    setI(STEPS.length - 1);
    const t = window.setTimeout(() => setPlayed(true), 600);
    return () => clearTimeout(t);
  }, [ready]);

  /**
   * <b>오래 걸리면 그렇다고 말한다.</b> 진행 바가 마지막 앞에서 멈춰 있는 동안 아무 말이
   * 없으면 사용자는 멈춘 줄 안다. 12초가 넘으면 한 줄을 더한다.
   */
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    setSlow(false);
    const t = window.setTimeout(() => setSlow(true), 12_000);
    return () => clearTimeout(t);
  }, [tick]);

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
            {slow && !ready && (
              <p className="load-step" style={{ marginTop: 8, opacity: .75 }}>
                결제가 많으면 조금 더 걸려요. 그대로 두시면 돼요.
              </p>
            )}
          </>
        )}
      </div>
    </Screen>
  );
}
