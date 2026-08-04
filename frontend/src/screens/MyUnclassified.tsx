/**
 * 마이 &gt; 분류 정리 — 무엇에 썼는지 모르는 결제를 사람이 정리한다.
 *
 * 실제 카드 명세서에는 <b>업종코드가 없다.</b> 그래서 그대로 넣으면 '카테고리없음'만 쌓이고,
 * 리포트도 지킴이도 그만큼 눈이 먼다. 그렇다고 AI 가 알아서 정하게 두면 판정이 AI 를 타게 된다
 * (설계원칙 1 — 판단은 설명가능한 모델이).
 *
 * 그래서 <b>AI 는 제안만 하고 확정은 사람이</b> 한다. 추정은 "AI 추정" 배지로만 보이고,
 * 사용자가 고른 것이 저장된다. 한 번 확정한 가맹점은 사전에 쌓여 <b>다시 묻지 않는다</b>.
 */
import { useState } from 'react';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty, SectionTitle } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, type UnclassifiedItem } from '../lib/api';
import { shortDate } from '../lib/format';

export function MyUnclassified() {
  const { back, userId } = useSession();
  const list = useAsync(() => api.unclassified(userId), [userId]);
  const [busy, setBusy] = useState<string | null>(null);
  const [done, setDone] = useState<Record<string, string>>({});
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);

  const items: UnclassifiedItem[] = list.data?.items ?? [];
  const categories: string[] = list.data?.categories ?? [];
  const remaining = items.filter((it) => !done[it.paymentId]);

  async function confirm(it: UnclassifiedItem, category2: string) {
    setBusy(it.paymentId); setError(null); setMsg(null);
    try {
      const res = await api.confirmCategory(userId, it.paymentId, category2);
      setDone((d) => ({ ...d, [it.paymentId]: category2 }));
      // 사전에 쌓였는지 알려 준다 — "다시 안 묻는다"는 것이 이 기능의 값어치다.
      setMsg(res.storedInDictionary
        ? `${it.merchantName ?? '이 결제'} — ${category2}으로 저장했어요. 다음부터 안 물어볼게요`
        : `${it.merchantName ?? '이 결제'} — ${category2}으로 바꿨어요`);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(null);
    }
  }

  return (
    <Screen title="분류 정리" hasTabBar>
      <AppBar onBack={back} title="분류 정리" />
      <Scroll>
        {list.loading && <Loading label="분류할 결제를 찾는 중" />}
        {!!list.error && <ErrorBox error={list.error} onRetry={list.reload} />}

        {!list.loading && !list.error && (
          <>
            <SectionTitle>
              {remaining.length > 0
                ? `무엇에 썼는지 모르는 결제 ${remaining.length}건`
                : '정리할 결제'}
            </SectionTitle>

            {list.data && !list.data.aiEnabled && (
              <p style={{ color: 'var(--t3)', fontSize: 13, margin: '0 0 12px' }}>
                지금은 AI 추정을 쓸 수 없어요. 직접 골라 주시면 저장할게요.
              </p>
            )}

            {remaining.length === 0 && (
              <Empty>정리할 결제가 없어요. 모두 분류돼 있어요.</Empty>
            )}

            {remaining.map((it) => (
              <div key={it.paymentId} style={card}>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                  <div style={{ minWidth: 0 }}>
                    <div style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {it.merchantName ?? '이름 없는 결제'}
                    </div>
                    <div style={{ color: 'var(--t3)', fontSize: 12, marginTop: 2 }}>
                      {shortDate(it.date)} · {it.amount.toLocaleString()}원
                    </div>
                  </div>
                  {it.suggested && (
                    // 추정임을 숨기지 않는다. 사용자가 무엇을 믿고 고르는지 알아야 한다.
                    <span style={badge}>AI 추정 · {it.suggested}</span>
                  )}
                </div>

                <div style={chips}>
                  {/* 추정이 있으면 맨 앞에 둔다 — 대부분 그것을 고른다. */}
                  {it.suggested && (
                    <button
                      style={{ ...chip, ...chipPrimary }}
                      disabled={busy === it.paymentId}
                      onClick={() => confirm(it, it.suggested!)}
                    >
                      맞아요 · {it.suggested}
                    </button>
                  )}
                  {categories
                    .filter((c) => c !== it.suggested)
                    .map((c) => (
                      <button
                        key={c}
                        style={chip}
                        disabled={busy === it.paymentId}
                        onClick={() => confirm(it, c)}
                      >
                        {c}
                      </button>
                    ))}
                </div>
              </div>
            ))}

            {msg && <p style={{ color: 'var(--t2)', fontSize: 13, marginTop: 12 }}>{msg}</p>}
            {!!error && <ErrorBox error={error} />}
          </>
        )}
      </Scroll>
    </Screen>
  );
}

const card: React.CSSProperties = {
  background: 'var(--surface)', borderRadius: 14, padding: 14, marginBottom: 10,
};
const badge: React.CSSProperties = {
  flexShrink: 0, alignSelf: 'flex-start', fontSize: 11, padding: '3px 8px',
  borderRadius: 999, background: 'var(--amber-t)', color: 'var(--t1)',
};
const chips: React.CSSProperties = {
  display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 12,
};
const chip: React.CSSProperties = {
  fontSize: 12, padding: '6px 10px', borderRadius: 999,
  border: '1px solid var(--line)', background: 'transparent', color: 'var(--t2)',
};
const chipPrimary: React.CSSProperties = {
  border: 'none', background: 'var(--accent)', color: '#fff', fontWeight: 600,
};
