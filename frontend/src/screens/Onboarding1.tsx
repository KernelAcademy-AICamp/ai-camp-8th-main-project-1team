/**
 * 이번 챌린지 정하기 1/4 — AN-01 소비 분석 요약 + AN-02 가치 소비(성역) 확인.
 *
 * ① 분석이 찾은 반복 소비와 조정 가능한 카테고리를 **낙인 없이** 보여주고,
 * 줄이고 싶지 않은 소비를 고르게 한다. 고른 카테고리는 챌린지의 `sanctuaryCategories`로 넘어가
 * 지킴이가 그 결제에는 먼저 침묵한다(설계서 C4).
 */
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

  const fixed = a.recurring.filter((r) => r.type === 'FIXED').slice(0, 6);
  const topDow = DOW_KR[a.pattern.peak?.dayOfWeek ?? topKey(a.pattern.amountByDayOfWeek)] ?? '금';
  const topPart = a.pattern.peak?.daypart ?? topKey(a.pattern.amountByDaypart) ?? '저녁';
  const candidates = a.cutCandidates.slice(0, 5);

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
        <p className="h-sub">지킴이가 그동안의 소비를 살펴봤어요. 이 중에서 함께 줄여볼 곳을 곧 골라요.</p>

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
                    <span className="amt">{won(c.monthlySpend)}</span>
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
            <p className="label">
              그동안 매달 빠져나간 고정지출이에요 <span style={{ color: 'var(--t3)', fontWeight: 600 }}>(못 줄여요)</span>
            </p>
            <div className="chips">
              {fixed.map((f, i) => (
                <span key={`${f.merchantName ?? f.category2}-${i}`} className="chip static">
                  {f.merchantName ?? f.category2} · {won(f.representativeAmount)}
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
        <button type="button" className="btn btn-primary" onClick={() => go('ob2')}>줄일 카테고리 고르기</button>
      </Cta>
    </Screen>
  );
}
