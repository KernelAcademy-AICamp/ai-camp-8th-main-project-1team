/**
 * 알림함 (프로토타입_0806 `s-notify`) — 지킴이가 한 말을 모아 둔 곳.
 *
 * <b>카드 하나가 알림 하나다.</b> 예전에는 목록 줄로 늘어놓고 줄마다 피드백 버튼 두 개를
 * 달았다. 알림이 다섯이면 버튼이 열 개가 되어 화면이 버튼밭이 됐다 — 읽으러 온 화면인데.
 * 이제 종류(아이콘·라벨)와 시각을 머리에, 제목과 본문을 몸통에 두고, <b>피드백은 눌러서 편다.</b>
 *
 * <b>같은 종류가 잇달아 오면 접는다.</b> 지출 확인 알림 셋이 따로 서면 알림함이 로그가 되고
 * 정작 읽어야 할 예산 초과가 묻힌다. 첫 장만 펴 두고 나머지는 '더보기'로 접는다.
 *
 * <b>침묵도 기록이다.</b> 안 보낸 알림(`SILENT`)은 여기 안 뜬다 — 사용자에게는 일어나지 않은
 * 일이다. 그 기록은 운영이 본다.
 */
import { useState } from 'react';
import { Icon } from '../components/Icons';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api, type FeedbackReason, type GuardianNotification } from '../lib/api';

const REASONS: { key: FeedbackReason; label: string }[] = [
  { key: 'TIMING', label: '타이밍' },
  { key: 'TONE', label: '말투' },
  { key: 'ALREADY_KNEW', label: '이미 알아요' },
  { key: 'NOT_MINE', label: '내 소비 아님' },
  { key: 'TOO_OFTEN', label: '너무 자주' },
];

/**
 * 케이스 → 종류 이름과 아이콘.
 *
 * <b>케이스 id 를 화면에 그대로 보이지 않는다.</b> 'C3' 는 서버의 말이지 사용자의 말이 아니다.
 * 모르는 케이스는 '지킴이'로 떨어뜨린다 — 새 케이스가 생겨도 화면이 깨지지 않는다.
 */
const KIND: Record<string, { icon: string; label: string }> = {
  C1: { icon: 'i-card', label: '지출 확인' },
  C2: { icon: 'i-card', label: '지출 확인' },
  C3: { icon: 'i-shield', label: '예산' },
  C5: { icon: 'i-flame', label: '무지출' },
  C6: { icon: 'i-shield', label: '예산' },
  C7: { icon: 'i-card', label: '지출 확인' },
  C8: { icon: 'i-card', label: '지출 확인' },
  C9: { icon: 'i-bell', label: '미리 알림' },
  C10: { icon: 'i-chart', label: '마무리' },
  C11: { icon: 'i-chart', label: '마무리' },
  M1: { icon: 'i-gift', label: '마이룸' },
  W1: { icon: 'i-chart', label: '리포트' },
};
const kindOf = (caseId: string) => KIND[caseId] ?? { icon: 'i-shield', label: '지킴이' };

/** "37분 전" · "2시간 전" · "4일 전". 알림함에서는 절대시각보다 경과가 읽힌다. */
function ago(iso: string | null): string {
  if (!iso) return '';
  const min = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
  if (min < 1) return '방금';
  if (min < 60) return `${min}분 전`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}시간 전`;
  return `${Math.floor(hr / 24)}일 전`;
}

function Card({ note, userId, onDone }: {
  note: GuardianNotification; userId: number; onDone: () => void;
}) {
  const [given, setGiven] = useState<string | null>(note.feedback);
  const [asking, setAsking] = useState(false);
  const [open, setOpen] = useState(false);
  const k = kindOf(note.caseId);

  async function send(feedback: 'USEFUL' | 'NOT_USEFUL', reason?: FeedbackReason) {
    setGiven(feedback);
    setAsking(false);
    setOpen(false);
    await api.guardian.feedback(userId, note.id, feedback, reason).catch(() => undefined);
    onDone();
  }

  return (
    <div className="nt-card">
      <div className="nt-top">
        <Icon id={k.icon} className="ci" />
        <span className="ncat">{k.label}</span>
        {/* 아직 답하지 않은 알림에만 점을 찍는다 — 읽은 것은 자리를 비워 색 없이도 갈린다. */}
        {!given && <span className="ndot" aria-label="답하지 않음" />}
        <span className="ntime">{ago(note.sentAt)}</span>
      </div>
      <b>{note.title ?? '지킴이'}</b>
      <p>{note.body}</p>
      {note.isFallback && <div className="nt-extra">기본 문구로 보냈어요</div>}

      {given ? (
        <div className="nt-extra">
          {given === 'USEFUL' ? '도움이 됐다고 알려줬어요 — 고마워요'
            : '알려줘서 고마워요. 다음엔 다르게 말해볼게요'}
        </div>
      ) : !open ? (
        <button type="button" className="nt-more" onClick={() => setOpen(true)}>
          이 알림 어땠어요? <span aria-hidden="true">›</span>
        </button>
      ) : (
        <>
          <div className="ctx3" style={{ marginTop: 10 }}>
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

/** 같은 종류가 잇달아 온 묶음 — 첫 장만 펴 두고 나머지는 접는다. */
function Stack({ notes, userId, onDone }: {
  notes: GuardianNotification[]; userId: number; onDone: () => void;
}) {
  const [open, setOpen] = useState(false);
  if (notes.length === 1) return <Card note={notes[0]} userId={userId} onDone={onDone} />;
  const shown = open ? notes : notes.slice(0, 1);
  return (
    <>
      {shown.map((n) => <Card key={n.id} note={n} userId={userId} onDone={onDone} />)}
      {!open && (
        <button type="button" className="nt-more" style={{ marginTop: -6, marginBottom: 12 }}
          onClick={() => setOpen(true)}>
          {notes.length - 1}개 더보기 <span aria-hidden="true">›</span>
        </button>
      )}
    </>
  );
}

export function Notifications() {
  const { back, userId } = useSession();
  const { reload } = useGuardian();
  const notes = useAsync(() => api.guardian.notifications(userId), [userId]);

  /**
   * 잇달아 온 같은 종류를 한 묶음으로.
   *
   * <b>종류가 같아도 사이에 다른 알림이 끼면 나눈다.</b> 전체를 종류별로 모으면 시간 순서가
   * 깨져 "어제 것 다음에 오늘 것"이 되고, 알림함에서 순서는 곧 맥락이다.
   */
  const groups: GuardianNotification[][] = [];
  for (const n of notes.data?.notifications ?? []) {
    const last = groups[groups.length - 1];
    if (last && kindOf(last[0].caseId).label === kindOf(n.caseId).label) last.push(n);
    else groups.push([n]);
  }

  return (
    <Screen title="알림">
      <AppBar title="알림" onBack={back} />
      <Scroll><div className="pad" style={{ paddingTop: 8 }}>
        {notes.loading && <Loading label="알림을 불러오는 중" rows={3} />}
        <ErrorBox error={notes.error} onRetry={notes.reload} />

        {notes.data && groups.length === 0 && (
          <div className="card"><Empty>아직 온 알림이 없어요. 조용한 건 잘 지키고 있다는 뜻이에요.</Empty></div>
        )}

        {groups.map((g) => (
          <Stack key={g[0].id} notes={g} userId={userId}
            onDone={() => { notes.reload(); reload(); }} />
        ))}

        <div className="spacer" style={{ height: 32 }} />
      </div></Scroll>
    </Screen>
  );
}
