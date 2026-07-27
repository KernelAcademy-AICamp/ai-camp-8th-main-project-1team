/**
 * 마이 &gt; 개인정보 처리방침 (SET-04).
 * 문안을 화면에 하드코딩하지 않고 `/api/privacy/policy`에서 읽는다 —
 * 방침을 고칠 때 정본(legal/privacy-policy.md)·백엔드·화면이 따로 노는 것을 막기 위함이다.
 */
import { AppBar, Scroll, Screen, ErrorBox, Loading } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';

export function MyPolicy() {
  const { back } = useSession();
  const policy = useAsync(() => api.privacyPolicy(), []);

  return (
    <Screen title="개인정보 처리방침" hasTabBar>
      <AppBar onBack={back} title="개인정보 처리방침" />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        <ErrorBox error={policy.error} onRetry={policy.reload} />
        {policy.loading && <Loading label="처리방침을 불러오는 중" rows={6} />}

        {policy.data && (
          <>
            <p className="h-title" style={{ marginTop: 0 }}>{policy.data.title}</p>
            <div className="card">
              {policy.data.clauses.map((c) => (
                <section key={c.title} style={{ marginBottom: 18 }}>
                  <h2 style={{ fontSize: 15.5, fontWeight: 700, margin: '0 0 6px' }}>{c.title}</h2>
                  <p style={{ margin: 0, fontSize: 14, lineHeight: 1.7, color: 'var(--t2)', whiteSpace: 'pre-line' }}>
                    {c.body}
                  </p>
                </section>
              ))}
            </div>
            <div className="pv">{policy.data.notice}</div>
          </>
        )}
        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
