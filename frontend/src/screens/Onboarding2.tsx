/**
 * 이번 챌린지 정하기 2/4 — CT-01 줄일 카테고리 선택. 사용자가 1~2개 확정한다.
 *
 * 후보와 기준 지출은 `/api/report/monthly`(= 분석 엔진의 카테고리 집계)에서 가져온다.
 * **월평균은 서버가 계산한 `monthlyAmount`를 그대로 쓴다.** 예전에는 화면이 직접
 * `총액 ÷ 전체 관측 개월수`로 나눴는데, 지킴이 서버는 카테고리별 개월수로 나누므로 둘이 달랐다 —
 * 화면에 보여준 금액과 서버가 잡는 기준이 어긋나면 강도 화면의 '지킬 돈'이 거짓말이 된다.
 *
 * `negative`(줄이면 좋은 소비 — ML 낭비 판정 또는 쏠림 기준)가 AI 추천 후보다.
 */
import { useEffect, useMemo, useRef } from 'react';
import { Icon } from '../components/Icons';
import { AppBar, ProgressBar, Cta, Scroll, Screen, ErrorBox, Loading, Empty } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, catLabel, type ReportLine } from '../lib/api';
import { won, iconOf } from '../lib/format';

export function Onboarding2() {
  const { go, back, userId, analysis, draft, patchDraft } = useSession();
  const report = useAsync(() => api.report(userId), [userId]);
  const inited = useRef(false);

  const { options, recommended } = useMemo(() => {
    const r = report.data;
    if (!r) return { options: [] as (ReportLine & { monthly: number; rec: boolean })[], recommended: [] as string[] };
    const mark = (lines: ReportLine[], rec: boolean) =>
      lines.map((l) => ({ ...l, monthly: l.monthlyAmount, rec }));
    const all = [...mark(r.negative, true), ...mark(r.positive, false)]
      .filter((l) => l.monthly > 0)
      .sort((x, y) => y.monthly - x.monthly);
    return { options: all, recommended: all.filter((l) => l.rec).slice(0, 2).map((l) => l.categoryCode) };
  }, [report.data]);

  /** ① 분석의 절약 후보(category2 단위) 근거 문장을 카테고리 이름으로 이어 붙인다. */
  const reasonOf = useMemo(() => {
    const map = new Map<string, string>();
    (analysis?.cutCandidates ?? []).forEach((c) => map.set(c.category2, c.reason));
    return (displayName: string) => {
      for (const [name, reason] of map) {
        if (name.includes(displayName) || displayName.includes(name)) return reason;
      }
      return null;
    };
  }, [analysis]);

  // 첫 진입 시 AI 추천 상위 2개를 미리 선택한다(사용자가 해제 가능 — IA CT-01).
  useEffect(() => {
    if (inited.current || options.length === 0) return;
    inited.current = true;
    const baseline: typeof draft.baseline = {};
    options.forEach((o) => {
      baseline[o.categoryCode] = {
        displayName: catLabel(o.categoryCode, o.displayName),
        monthlyAmount: o.monthly,
        reason: reasonOf(o.displayName) ?? undefined,
        type: o.rec ? 'RECOMMENDED' : 'OTHER',
      };
    });
    patchDraft({ baseline, cutCats: draft.cutCats.length ? draft.cutCats : recommended });
  }, [options, recommended, reasonOf, patchDraft, draft.cutCats, draft.baseline]);

  const toggle = (code: string) => {
    const on = draft.cutCats.includes(code);
    patchDraft({ cutCats: on ? draft.cutCats.filter((k) => k !== code) : [...draft.cutCats, code] });
  };

  return (
    <Screen title="줄일 카테고리 선택">
      <AppBar onBack={back} steps="2 / 4" />
      <ProgressBar value={0.5} />
      <Scroll><div className="pad">
        <p className="h-title">뭘 줄여볼까요?</p>
        <p className="h-sub">
          지킴이가 <b style={{ color: 'var(--blue-t)' }}>AI 추천</b>으로 골라봤어요. 1~2개 권장.
          금액은 <b>한 달 평균 지출</b>이에요 — 다음에서 강도로 실제 지킬 돈을 정해요.
        </p>

        <ErrorBox error={report.error} onRetry={report.reload} />
        {report.loading && <Loading label="카테고리를 불러오는 중" rows={4} />}
        {!report.loading && options.length === 0 && !report.error && (
          <Empty>아직 카테고리별 소비가 쌓이지 않았어요. 카드 연결 뒤 결제가 들어오면 후보가 생겨요.</Empty>
        )}

        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {options.map((c) => {
            const on = draft.cutCats.includes(c.categoryCode);
            const name = catLabel(c.categoryCode, c.displayName);
            const { icon, bg } = iconOf(name);
            const reason = reasonOf(c.displayName);
            return (
              <button type="button" key={c.categoryCode} onClick={() => toggle(c.categoryCode)} aria-pressed={on}
                className="card" style={{
                  margin: 0, padding: 16, cursor: 'pointer', position: 'relative', textAlign: 'left',
                  fontFamily: 'inherit', width: '100%',
                  border: `1.5px solid ${on ? 'var(--blue)' : 'var(--line)'}`,
                  background: on ? 'var(--blue-weak)' : 'var(--card)',
                }}>
                {c.rec && (
                  <span style={{ position: 'absolute', top: -8, right: 14, fontSize: 10, fontWeight: 700, background: 'var(--blue)', color: '#fff', padding: '2px 8px', borderRadius: 20 }}>
                    AI 추천
                  </span>
                )}
                <span className="list-item" style={{ padding: 0 }}>
                  <span className="ic" style={{ background: bg }}><Icon id={icon} /></span>
                  <span className="tx">
                    <b>{name} <span style={{ fontSize: 12, color: 'var(--t3)', fontWeight: 600 }}>
                      {c.rec ? '줄이면 좋아요' : '잘 관리 중'}</span></b>
                    <span>{reason ?? `최근 ${c.count.toLocaleString('ko-KR')}건 · 전체의 ${c.spendPercent}%`}</span>
                  </span>
                  <span style={{ textAlign: 'right', flex: '0 0 auto' }}>
                    <b style={{ color: 'var(--t1)', fontSize: 15, display: 'block' }}>{won(c.monthly)}</b>
                    <span style={{ fontSize: 11, color: 'var(--t3)' }}>한 달 평균</span>
                  </span>
                </span>
              </button>
            );
          })}
        </div>
        <div className="spacer" />
      </div></Scroll>
      <Cta>
        <button type="button" className="btn btn-primary" disabled={draft.cutCats.length === 0} onClick={() => go('ob3')}>
          {draft.cutCats.length === 0 ? '줄일 소비를 골라주세요' : `${draft.cutCats.length}개로 시작하기`}
        </button>
      </Cta>
    </Screen>
  );
}
