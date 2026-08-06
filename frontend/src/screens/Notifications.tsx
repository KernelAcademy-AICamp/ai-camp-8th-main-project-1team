/**
 * NT-01 알림함 — 지킴이가 실제로 말한 것만 보여준다.
 * 침묵(delivery=SILENT)은 서버가 목록에서 빼고 내려준다. 침묵도 하나의 결정이라 기록은 남지만
 * 사용자에게 보일 것은 아니기 때문이다(설계서 §3.2).
 *
 * 각 알림에는 피드백을 남길 수 있다 — 별점이 아니라 사유 태그다. 프롬프트를 어느 방향으로
 * 고칠지는 사유가 정한다(설계서 §API 5).
 */
import { useState } from 'react';
import { Orb, AppBar, Scroll, Screen, ErrorBox, Loading, Empty } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api, type FeedbackReason, type GuardianNotification } from '../lib/api';
import { shortDateTime } from '../lib/format';

const REASONS: { key: FeedbackReason; label: string }[] = [
  { key: 'TIMING', label: '타이밍' },
  { key: 'TONE', label: '말투' },
  { key: 'ALREADY_KNEW', label: '이미 알아요' },
  { key: 'NOT_MINE', label: '내 소비 아님' },
  { key: 'TOO_OFTEN', label: '너무 자주' },
];

function Row({ note, userId, onDone }: { note: GuardianNotification; userId: number; onDone: () => void }) {
  const [given, setGiven] = useState<string | null>(note.feedback);
  const [asking, setAsking] = useState(false);

  async function send(feedback: 'USEFUL' | 'NOT_USEFUL', reason?: FeedbackReason) {
    setGiven(feedback);
    setAsking(false);
    await api.guardian.feedback(userId, note.id, feedback, reason).catch(() => undefined);
    onDone();
  }

  return (
    <div style={{ padding: '14px 0', borderBottom: '1px solid var(--bg)' }}>
      <div className="list-item" style={{ padding: 0, alignItems: 'flex-start' }}>
        <Orb size={34} style={{ marginTop: 2 }} />
        <div className="tx">
          <b style={{ fontSize: 15 }}>{note.title ?? '지킴이'}</b>
          <p style={{ margin: '3px 0 0', fontSize: 14.5, lineHeight: 1.5, color: 'var(--t1)' }}>{note.body}</p>
          <span style={{ display: 'block', marginTop: 6 }}>
            {note.sentAt ? shortDateTime(note.sentAt) : ''}
            {note.isFallback && <span className="aux-badge" style={{ marginLeft: 6 }}>기본 문구</span>}
          </span>
        </div>
      </div>

      {given ? (
        <div className="ctx-fb">
          {given === 'USEFUL' ? '도움이 됐다고 알려줬어요 — 고마워요' : '알려줘서 고마워요. 다음엔 다르게 말해볼게요'}
        </div>
      ) : (
        <>
          <div className="ctx3">
            <span style={{ fontSize: 12, color: 'var(--t3)', marginRight: 2 }}>이 알림:</span>
            <button type="button" onClick={() => void send('USEFUL')}>도움됐어요</button>
            <button type="button" onClick={() => setAsking((v) => !v)} aria-expanded={asking}>별로였어요</button>
          </div>
          {asking && (
            <div className="ctx3" style={{ marginTop: 6 }}>
              {REASONS.map((r) => (
                <button type="button" key={r.key} onClick={() => void send('NOT_USEFUL', r.key)}>{r.label}</button>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}

export function Notifications() {
  const { back, userId } = useSession();
  const { reload } = useGuardian();
  const notes = useAsync(() => api.guardian.notifications(userId), [userId]);
  const list = notes.data?.notifications ?? [];

  return (
    <Screen title="알림함" hasTabBar>
      <AppBar onBack={back} title="알림함" />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        <p className="h-sub" style={{ margin: '0 0 12px' }}>
          예산 안에서 쓴 결제는 알리지 않아요. 지킴이는 필요할 때만 말합니다.
        </p>

        <ErrorBox error={notes.error} onRetry={notes.reload} />
        {notes.loading && <Loading label="알림을 불러오는 중" rows={4} />}

        {!notes.loading && list.length === 0 && !notes.error && (
          <div className="card"><Empty>아직 온 알림이 없어요. 조용한 건 잘 지키고 있다는 뜻이에요.</Empty></div>
        )}

        {list.length > 0 && (
          <div className="card" style={{ padding: '4px 18px' }}>
            {list.map((n) => (
              <Row key={n.id} note={n} userId={userId} onDone={() => { notes.reload(); void reload(); }} />
            ))}
          </div>
        )}

        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
