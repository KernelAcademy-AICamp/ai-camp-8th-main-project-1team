/**
 * 마이 &gt; 설정 &gt; 지킴이 말수 설정 (프로토타입_0806 `s-my` 설정 1번째 줄).
 *
 * <p><b>말수는 신뢰의 문제다.</b> 하루에 다섯 번 말을 걸면 여섯 번째부터는 아무도 안 읽는다.
 * 반대로 너무 조용하면 지켜보고 있다는 느낌이 사라진다. 그 균형점이 사람마다 달라서,
 * 운영이 정한 기본값(전역 설정) 위에 <b>사용자가 정한 값</b>을 얹는다.
 *
 * <p><b>예산 초과 통보는 이 상한을 넘겨서라도 나간다.</b> 말수를 줄인 것이지 위험을 알리지
 * 말라고 한 것이 아니다 — 그 구분이 없으면 조용히 설정한 사람이 정작 필요할 때 못 듣는다.
 */
import { useEffect, useState } from 'react';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Cta } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';

/** 고를 수 있는 말수. 0은 '설정 안 함'이라 전역 기본값을 따른다. */
const LEVELS = [
  { value: 1, label: '조용히', caption: '하루 최대 1번 · 꼭 필요할 때만' },
  { value: 2, label: '보통', caption: '하루 최대 2번 · 기본값' },
  { value: 4, label: '자주', caption: '하루 최대 4번 · 자주 짚어줘요' },
];

export function MyVoice() {
  const { back, userId } = useSession();
  const state = useAsync(() => api.guardian.voice(userId), [userId]);
  const [picked, setPicked] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [saved, setSaved] = useState(false);

  // 서버 값으로 시작한다. 설정 안 한 사람은 전역 기본값이 골라진 것으로 보인다 —
  // "아무것도 안 골라져 있음"보다 "지금은 이 값"이 읽기 쉽다.
  useEffect(() => {
    if (state.data) setPicked(state.data.dailyLimit || state.data.defaultLimit);
  }, [state.data]);

  if (state.loading && !state.data) return <Loading label="설정을 불러오는 중" />;

  const dirty = picked !== null && state.data != null
    && picked !== (state.data.dailyLimit || state.data.defaultLimit);

  async function save() {
    if (picked === null) return;
    setBusy(true); setError(null);
    try {
      await api.guardian.setVoice(userId, picked);
      state.reload();
      setSaved(true);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Screen title="지킴이 말수 설정">
      <AppBar onBack={back} title="지킴이 말수 설정" />
      <Scroll><div className="pad">
        <p className="h-title">얼마나 자주<br />말을 걸까요?</p>
        <p className="h-sub">
          하루에 보낼 알림의 최대 수예요. 넘으면 지킴이가 조용히 있어요.
        </p>

        <ErrorBox error={state.error ?? error} onRetry={state.reload} />

        <div className="seg" style={{ flexDirection: 'column', gap: 10 }}>
          {LEVELS.map((l) => {
            const on = picked === l.value;
            return (
              <button type="button" key={l.value} aria-pressed={on}
                className={on ? 'on' : undefined}
                style={{ textAlign: 'left', padding: '16px 18px' }}
                onClick={() => { setPicked(l.value); setSaved(false); }}>
                <b style={{ display: 'block', fontSize: 15 }}>{l.label}</b>
                <span style={{ display: 'block', marginTop: 4, fontSize: 12.5,
                  color: 'var(--t3)', fontWeight: 500 }}>{l.caption}</span>
              </button>
            );
          })}
        </div>

        <p className="pv">
          <b>예산을 넘긴 알림은 이 수를 넘겨서라도 보내요.</b> 말수를 줄인 것이지,
          위험을 알리지 말라고 하신 건 아니니까요.
        </p>
        {saved && !dirty && (
          <p className="pv" role="status" style={{ color: 'var(--green-t)' }}>
            저장했어요. 오늘부터 이만큼만 말할게요.
          </p>
        )}
        <div className="spacer" />
      </div></Scroll>
      <Cta>
        <button type="button" className="btn btn-primary" disabled={!dirty || busy}
          onClick={() => void save()}>
          {busy ? '저장하는 중…' : dirty ? '이대로 저장' : '바뀐 것이 없어요'}
        </button>
      </Cta>
    </Screen>
  );
}
