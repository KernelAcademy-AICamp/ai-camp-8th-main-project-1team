/**
 * 이번 챌린지 정하기 1/4 — AN-01 소비 분석 요약 + AN-02 가치 소비(성역) 확인.
 *
 * ① 분석이 찾은 반복 소비와 조정 가능한 카테고리를 **낙인 없이** 보여주고,
 * 줄이고 싶지 않은 소비를 고르게 한다. 고른 카테고리는 챌린지의 `sanctuaryCategories`로 넘어가
 * 지킴이가 그 결제에는 먼저 침묵한다(설계서 C4).
 */
import { useState } from 'react';
import { Icon } from '../components/Icons';
import { AppBar, ProgressBar, Cta, Scroll, Screen, ErrorBox, Loading } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, catLabel } from '../lib/api';
import { won, iconOf, DOW_KR } from '../lib/format';

const topKey = (m: Record<string, number>) =>
  Object.entries(m).sort((a, b) => b[1] - a[1])[0]?.[0] ?? '';

export function Onboarding1() {
  const { go, userId, analysis, setAnalysis, draft, patchDraft } = useSession();
  // 로딩 화면을 건너뛰고 바로 들어온 경우(주소로 진입)를 대비해 여기서도 분석을 확보한다.
  const fetched = useAsync(
    () => (analysis ? Promise.resolve(analysis) : api.analysis(userId).then((a) => { setAnalysis(a); return a; })),
    [userId],
  );
  const cats = useAsync(() => api.categories().catch(() => []), [userId]);
  // 다음 화면(ob2)과 **같은 창**을 본다. 예전에는 여기가 최근 90일 환산, 저기가 전 기간 평균이라
  // 같은 카테고리 금액이 화면을 넘길 때 튀었다(2026-07-31 실측 691,150 vs 745,118).
  const win = useAsync(() => api.onboardingWindow(userId).catch(() => null), [userId]);
  /** 이미 답해 둔 가맹점 — 같은 것을 두 번 묻지 않는다. */
  const stances = useAsync(() => api.merchantStances(userId).catch(() => null), [userId]);
  const [askOpen, setAskOpen] = useState(false);
  const [askDone, setAskDone] = useState(false);

  const a = analysis ?? fetched.data;
  if (fetched.error) {
    return (
      <Screen title="소비 분석 요약">
        <AppBar title="분석 결과" />
        <div className="pad"><ErrorBox error={fetched.error} onRetry={fetched.reload} /></div>
      </Screen>
    );
  }
  if (!a) {
    return (
      <Screen title="소비 분석 요약">
        <AppBar title="분석 결과" />
        <div className="pad"><Loading label="분석 결과를 불러오는 중" rows={5} /></div>
      </Screen>
    );
  }

  // 끝난 구독은 뺀다 — 이 화면은 "그동안 꼬박꼬박 빠져나간" 지금의 고정지출을 말한다.
  const fixed = a.recurring.filter((r) => r.type === 'FIXED' && r.status === 'ACTIVE').slice(0, 6);
  /**
   * 넘어가기 전에 물어볼 반복 지출 하나 (개편안 `sheet-ktx`).
   *
   * <b>가장 큰 것 하나만 묻는다.</b> 고정지출이 여섯이라고 여섯 번 물으면 그건 설문이지
   * 확인이 아니다. 금액이 가장 큰 것 하나만 짚고, 나머지는 나중에 마이 &gt; 낭비 판정 관리에서 본다.
   *
   * 사업자번호가 없으면 묻지 않는다 — 답을 저장할 키가 없어 "답했다"를 기억하지 못한다.
   * 이미 느슨하게 보고 있는 곳도 빼야 물었던 것을 또 묻지 않는다.
   */
  const answeredBiz = new Set((stances.data?.items ?? []).map((s) => s.businessNumber));
  // **고정지출만 보지 않는다.** 개편안의 예시(주 2회 KTX)는 우리 모델에서 `FIXED`(매달 같은 날
  // 같은 금액)가 아니라 `ROUTINE`(같은 카테고리·시간대의 반복)으로 잡힌다. 둘 다 후보로 본다.
  const ask = a.recurring
    .filter((r) => r.status === 'ACTIVE')
    .filter((r) => (r.businessNumber
      ? !answeredBiz.has(r.businessNumber)          // 가맹점 단위로 이미 답한 것은 뺀다
      : !draft.sanctuary.includes(r.category2)))    // 카테고리 단위로 이미 성역이면 뺀다
    .sort((x, y) => y.representativeAmount - x.representativeAmount)[0] ?? null;

  /** 물을 것이 남았으면 먼저 묻고, 답했거나 물을 것이 없으면 그냥 넘어간다. */
  const closeAsk = () => setAskOpen(false);

  function next() {
    if (ask && !askDone && !stances.loading) { setAskOpen(true); return; }
    go('ob2');
  }

  /**
   * 답을 받아 넘어간다.
   *
   * <b>답을 어디에 적을지는 후보가 정한다.</b> 가맹점이 붙은 반복이면 그 가맹점만 빼고(정확),
   * 시간대 반복이라 가맹점이 없으면 그 카테고리를 성역으로 넣는다(할 수 있는 최선).
   * 없는 정밀도를 지어내지 않는다 — 가맹점을 모르는데 가맹점 설정을 만들 수는 없다.
   *
   * "줄일 수 있어요"는 아무것도 바꾸지 않는다. 지금 상태가 이미 '보통'이라 다시 쓸 것이 없다.
   */
  async function answerAsk(needed: boolean) {
    setAskDone(true);
    setAskOpen(false);
    if (needed && ask) {
      if (ask.businessNumber) {
        await api.excludeStance(userId, ask.businessNumber, ask.merchantName ?? undefined)
          .catch(() => undefined);
      } else if (!draft.sanctuary.includes(ask.category2)) {
        patchDraft({ sanctuary: [...draft.sanctuary, ask.category2] });
      }
    }
    go('ob2');
  }

  const topDow = DOW_KR[a.pattern.peak?.dayOfWeek ?? topKey(a.pattern.amountByDayOfWeek)] ?? '금';
  const topPart = a.pattern.peak?.daypart ?? topKey(a.pattern.amountByDaypart) ?? '저녁';
  const candidates = a.cutCandidates.slice(0, 5);
  /** 창 응답에서 그 카테고리의 실측 금액. 아직 안 왔거나 없으면 null(그때만 옛 값으로 뒤로 물러난다). */
  const windowAmount = (name: string) =>
    win.data?.categories.find((c) => c.displayName === name)?.amount ?? null;

  const toggleSanctuary = (code: string) => {
    const on = draft.sanctuary.includes(code);
    patchDraft({ sanctuary: on ? draft.sanctuary.filter((k) => k !== code) : [...draft.sanctuary, code] });
  };

  return (
    <Screen title="소비 분석 요약">
      <AppBar title="분석 완료" steps="1 / 4" />
      <ProgressBar value={0.25} />
      <Scroll><div className="pad">
        <p className="h-title">최근 소비를<br />이렇게 하고 있었어요</p>
        <p className="h-sub">지킴이가 <b>최근 30일</b> 소비를 살펴봤어요. 이 중에서 함께 줄여볼 곳을 곧 골라요.</p>

        {/* 소비 요약 — 습관 소비(줄일 후보 재료) */}
        {candidates.length > 0 ? (
          <div className="card" style={{ padding: '8px 20px' }}>
            {candidates.map((c, i) => {
              const { icon, bg } = iconOf(c.category2);
              return (
                <div key={c.category2}>
                  <div className="list-item">
                    <span className="ic" style={{ background: bg }}><Icon id={icon} /></span>
                    <div className="tx"><b>{c.category2}</b><span>{c.reason}</span></div>
                    {/* 최근 30일 **실측**이다. ob2·서버 기준 지출과 같은 값이라야
                        화면을 넘길 때 금액이 튀지 않는다. */}
                    <span className="amt">{won(windowAmount(c.category2) ?? c.monthlySpend)}</span>
                  </div>
                  {i < candidates.length - 1 && <div className="divider" />}
                </div>
              );
            })}
          </div>
        ) : (
          <div className="card">
            <p className="empty" style={{ margin: 0 }}>
              지금은 줄이라고 권할 만한 소비가 없어요. 좋은 흐름이에요 — 그래도 지켜볼 카테고리는 직접 고를 수 있어요.
            </p>
          </div>
        )}

        {/* 패턴 한마디 */}
        <div className="pv" style={{ margin: '0 0 14px' }}>
          <b>{topDow}요일 {topPart}</b>에 소비가 몰려요. 이런 순간을 지킴이가 같이 지켜볼게요.
        </div>

        {/* 고정지출 — 못 줄이는 소비로 분리 */}
        {fixed.length > 0 && (
          <>
            {/* '매달'이라고 쓰면 안 된다 — FIXED 판정에는 주간 주기(6~8일)도 들어온다.
                주 1회 7,000원이 "매달 7,000원"으로 보이면 실제 월 부담(약 3만원)의 1/4로 읽힌다.
                주기는 응답에 이미 있으므로 칩에 그대로 드러낸다. */}
            <p className="label">
              그동안 꼬박꼬박 빠져나간 고정지출이에요 <span style={{ color: 'var(--t3)', fontWeight: 600 }}>(못 줄여요)</span>
            </p>
            <div className="chips">
              {fixed.map((f, i) => (
                <span key={`${f.merchantName ?? f.category2}-${i}`} className="chip static">
                  {f.merchantName ?? f.category2} · {f.amountVaries ? '최근 ' : ''}{won(f.representativeAmount)}
                  {f.periodDays ? ` · ${f.periodDays}일마다` : ''}
                </span>
              ))}
            </div>
          </>
        )}

        {/* 가치 소비 칩(성역) — 선택 */}
        <p className="label">
          줄이고 싶지 않은 소비가 있나요? <span style={{ color: 'var(--green-t)' }}>(선택)</span>
        </p>
        <p className="h-sub" style={{ margin: '0 0 12px', fontSize: 13.5 }}>
          고른 소비는 지킴이가 <b style={{ color: 'var(--green-t)' }}>먼저 침묵</b>해요. 이번 챌린지의 집계에서 빠져요.
        </p>
        <div className="chips">
          {(cats.data ?? []).map((c) => {
            const on = draft.sanctuary.includes(c.code);
            const { icon } = iconOf(c.displayName);
            return (
              <button type="button" key={c.code} className={`chip sanctuary${on ? ' on' : ''}`}
                aria-pressed={on} onClick={() => toggleSanctuary(c.code)}>
                <Icon id={icon} className="ci" />{catLabel(c.code, c.displayName)}
              </button>
            );
          })}
          {cats.data?.length === 0 && <p className="empty">카테고리 목록을 불러오지 못했어요.</p>}
        </div>

        <div className="spacer" />
      </div></Scroll>
      <Cta>
        <button type="button" className="btn btn-primary" onClick={next}>줄일 카테고리 고르기</button>
      </Cta>

      {/* 반복 지출 확인 (개편안 `sheet-ktx`) — 넘어가기 전에 하나만 묻는다.
          <b>다음 화면이 "무엇을 줄일까"를 묻기 때문에</b> 먼저 물어야 한다. 통근 교통비를
          줄일 후보에 올려 두고 "이 중에 고르세요"라고 하면, 고를 수 없는 것이 목록을 차지한다. */}
      {/* 닫기를 인라인 화살표로 두지 않는다 — 정적 검사(check-a11y)가 태그를 `=>` 의 `>` 에서
          끊어 읽어 뒤의 `aria-hidden` 을 못 보고 '역할 없는 클릭 div' 로 신고한다. */}
      <div className={`tp-dim${askOpen ? ' show' : ''}`} onClick={closeAsk} aria-hidden="true" />
      <div className={`tp-sheet${askOpen ? ' show' : ''}`} role="dialog"
        aria-label="반복 지출 확인" aria-hidden={!askOpen}>
        <div className="tp-head">넘어가기 전에 하나만 확인할게요</div>
        <p className="tp-cap">
          {ask ? <>{ask.periodDays ? `${ask.periodDays}일마다 ` : '규칙적으로 '}
            {ask.merchantName ?? `${catLabel(ask.category2)}${ask.daypart ? ` ${ask.daypart}` : ''}`}에{' '}
            쓰시네요. 줄일 지출이 아닐 수 있어서요.</>
            : '규칙적으로 나가는 지출이 있어요.'}
        </p>
        {ask && (
          <div className="card" style={{ background: 'var(--bg)', padding: '4px 20px', margin: '0 0 16px' }}>
            <div className="list-item">
              <span className="ic" style={{ background: iconOf(catLabel(ask.category2)).bg }}>
                <Icon id={iconOf(catLabel(ask.category2)).icon} />
              </span>
              <div className="tx">
                <b>{ask.merchantName ?? catLabel(ask.category2)}</b>
                <span>
                  {ask.periodDays ? `${ask.periodDays}일마다` : '규칙적으로 반복'}
                  {!ask.merchantName && ask.daypart && ` · ${ask.daypart}`}
                </span>
              </div>
              <span className="amt">{won(ask.representativeAmount)}</span>
            </div>
          </div>
        )}
        <div className="seg">
          <button type="button" onClick={() => void answerAsk(true)}>꼭 필요해요</button>
          <button type="button" onClick={() => void answerAsk(false)}>줄일 수 있어요</button>
        </div>
        <p style={{ margin: '12px 0 0', fontSize: 12, color: 'var(--t3)', lineHeight: 1.4 }}>
          꼭 필요한 지출이면 절약 후보에서 빼고, 지킴이도 신경 쓰지 않아요.
          {ask && !ask.businessNumber
            && <> 가게를 특정할 수 없는 반복이라 <b>{catLabel(ask.category2)}</b> 전체를 빼요.</>}
        </p>
      </div>
    </Screen>
  );
}
