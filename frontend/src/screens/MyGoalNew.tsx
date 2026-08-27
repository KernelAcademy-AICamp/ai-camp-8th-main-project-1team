/**
 * 목표 정하기 — <b>화면 하나에서 네 걸음</b> (프로토타입_0825 `s-goal1`·`s-goal2`·`s-goalD`·`s-goal3`).
 *
 * <p>프로토타입은 화면 넷이지만 여기서는 하나다. 이유는 <b>온보딩(`ob`)을 합쳤을 때와 같다</b> —
 * 화면이 바뀔 때마다 사람은 "여기가 어디였지"를 다시 세우고, 뒤로 가면 앞의 선택이 살아 있는지
 * 확신하지 못한다. 걸음만 바뀌면 고른 것이 그대로 있는 게 눈에 보인다.
 *
 * <pre>
 *   1  어떤 목표를 설정할까요        이름 + 추천 칩
 *   2  얼마를 모아볼까요             금액 키패드 + 빠른 증액
 *   3  언제까지 모아볼까요           개월 스테퍼 → **매달 지킬 돈**을 내 평균과 견줌
 *   4  이루면 선물이 도착해요        보상 소품 고르기 → 시작
 * </pre>
 *
 * <h2>3걸음이 이 화면의 핵심이다</h2>
 *
 * <p>기간을 고르면 <b>매달 넣어야 하는 돈</b>이 곧바로 나오고, 그 옆에 <b>이 사람이 실제로
 * 매달 지켜 온 돈</b>이 함께 선다. 목표를 세울 때 사람이 가장 크게 틀리는 곳이 여기다 —
 * "석 달이면 되겠지"가 실제로는 자기 평균의 세 배일 때가 많다. 숫자를 나란히 놓으면
 * <b>말리지 않아도 스스로 고친다.</b>
 *
 * <p>계산은 서버가 한다(`monthlyRequired`·`monthlyAverageSaved`). 화면에서 다시 세지 않는다 —
 * 두 곳에서 세면 언젠가 두 값이 갈린다(마스터 §4 원칙 2).
 */
import { useMemo, useState } from 'react';
import { AmountKeypad } from '../components/AmountKeypad';
import { ItemGlyph } from '../components/ItemGlyph';
import { AppBar, Cta, ErrorBox, Screen, Scroll } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, type CollectionCell } from '../lib/api';
import { man, won } from '../lib/format';

/** 추천 목표 — 이름칸을 비워 두면 아무도 안 쓴다. 눌러서 시작하고 고쳐 쓰라고 둔다. */
const CHIPS: { name: string; emoji: string }[] = [
  { name: '여행 자금', emoji: '✈️' },
  { name: '노트북', emoji: '💻' },
  { name: '비상금', emoji: '🛟' },
  { name: '이사 자금', emoji: '🏠' },
  { name: '자기계발', emoji: '🎓' },
];

const STEPS = ['어떤 목표를 설정할까요?', '얼마를 모아볼까요?', '언제까지 모아볼까요?',
  '목표를 이루면\n마이룸에 선물이 도착해요'] as const;

export function MyGoalNew() {
  const { back, go, userId } = useSession();
  const snap = useAsync(() => api.points(userId), [userId]);
  const rewards = useAsync(() => api.guardian.collection(userId).catch(() => null), [userId]);

  const [step, setStep] = useState(0);
  const [name, setName] = useState('');
  const [emoji, setEmoji] = useState('🎯');
  const [amount, setAmount] = useState(0);
  const [months, setMonths] = useState(6);
  const [reward, setReward] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  /**
   * 내가 실제로 매달 지켜 온 돈.
   *
   * <p><b>목표가 아니라 스냅샷에서 읽는다.</b> 처음에는 `goals[0]` 에서 꺼냈는데, 그러면
   * **첫 목표를 만드는 사람에게는 늘 0** 이었다 — 꺼내 볼 목표가 아직 없기 때문이다.
   * 페이스 비교가 가장 필요한 순간이 정확히 그때다.
   */
  const myPace = snap.data?.monthlyAverageSaved ?? 0;
  /** 이 계획대로면 매달 얼마 — 서버 공식과 같다(목표액 ÷ 개월). */
  const required = months > 0 ? Math.round(amount / months) : 0;
  /** 내 평균의 몇 배인가. 평균이 없으면 견줄 수 없다. */
  const ratio = myPace > 0 ? required / myPace : 0;

  const paceText = useMemo(() => {
    if (amount <= 0) return '';
    if (myPace <= 0) return '아직 지킨 기록이 없어 견줄 수가 없어요. 시작하고 나서 알려드릴게요.';
    if (ratio <= 1) return `내 평균 ${man(myPace)} 안쪽이에요. 지금 페이스로 갈 수 있어요.`;
    if (ratio <= 1.5) return `내 평균 ${man(myPace)}보다 조금 높아요. 해볼 만해요.`;
    return `내 평균 ${man(myPace)}의 ${ratio.toFixed(1)}배예요. 기간을 늘리면 편해져요.`;
  }, [amount, myPace, ratio]);

  const canNext = step === 0 ? name.trim().length > 0
    : step === 1 ? amount > 0
      : true;

  async function start() {
    setBusy(true);
    setError(null);
    try {
      await api.createGoal(userId, name.trim(), emoji, amount,
        { months, ...(reward ? { rewardCode: reward } : {}) });
      go('m-goals');
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  /**
   * 보상 후보는 **아직 없는 소품**뿐이다. 이미 가진 것을 상으로 걸면 이뤄도 달라지는 것이
   * 없어 상이 아니다. 도감이 비었거나 다 모았으면 후보가 없고, 그때는 그냥 시작한다.
   */
  const items: CollectionCell[] = (rewards.data?.cells ?? []).filter((c) => !c.owned);

  return (
    <Screen title="목표 정하기" id="m-goal-new">
      <AppBar onBack={step === 0 ? back : () => setStep(step - 1)} title="목표 정하기"
              steps={`${step + 1}/4`} />
      <Scroll>
        <div className="pad">
          <h2 className="h-title" style={{ whiteSpace: 'pre-line' }}>{STEPS[step]}</h2>

          {step === 0 && (
            <>
              <div className="gs-field">
                <button type="button" className="gs-emoji" aria-label="이모지 바꾸기"
                        onClick={() => setEmoji(nextEmoji(emoji))}>{emoji}</button>
                <input className="inp" value={name} maxLength={20}
                       placeholder="목표 이름 (예: 파리 여행)"
                       onChange={(e) => setName(e.target.value)} />
              </div>
              <div className="gs-chips">
                {CHIPS.map((c) => (
                  <button key={c.name} type="button"
                          className={name === c.name ? 'on' : undefined}
                          onClick={() => { setName(c.name); setEmoji(c.emoji); }}>
                    {c.emoji} {c.name}
                  </button>
                ))}
              </div>
            </>
          )}

          {step === 1 && (
            <>
              <div className={amount > 0 ? 'gs-amt' : 'gs-amt empty'}>
                {amount > 0 ? won(amount) : '0원'}
                {amount > 0 && (
                  <button type="button" className="clr" aria-label="금액 비우기"
                          onClick={() => setAmount(0)}>✕</button>
                )}
              </div>
              <AmountKeypad value={amount} onChange={setAmount} />
            </>
          )}

          {step === 2 && (
            <>
              <div className="gd-stepper">
                <button type="button" className="gd-btn" aria-label="한 달 줄이기"
                        disabled={months <= 1}
                        onClick={() => setMonths(Math.max(1, months - 1))}>−</button>
                <span className="gd-val"><b>{months}</b>개월</span>
                <button type="button" className="gd-btn" aria-label="한 달 늘리기"
                        disabled={months >= 60}
                        onClick={() => setMonths(Math.min(60, months + 1))}>+</button>
              </div>
              <div className="gd-res">
                <div className="gd-r1"><span>매달 지킬 돈</span><b>{won(required)}</b></div>
                {myPace > 0 && (
                  <div className="gd-meter" aria-hidden="true">
                    <i style={{ width: `${Math.min(100, Math.round(100 / Math.max(1, ratio)))}%` }} />
                    <span>내 평균 {man(myPace)}</span>
                  </div>
                )}
                <p className="gd-r2">{paceText}</p>
              </div>
            </>
          )}

          {step === 3 && (
            <div className="gs-grid">
              {items.length === 0 && (
                <p className="gd-r2">고를 수 있는 소품이 아직 없어요. 그냥 시작해도 괜찮아요.</p>
              )}
              {items.map((it) => (
                <button key={it.code} type="button"
                        className={reward === it.code ? 'gs-obj on' : 'gs-obj'}
                        aria-pressed={reward === it.code}
                        onClick={() => setReward(reward === it.code ? null : it.code)}>
                  <ItemGlyph glyph={it.glyph} size={44} />
                  <span>{it.name}</span>
                </button>
              ))}
            </div>
          )}

          {error != null && <ErrorBox error={error} />}
          <div className="spacer" style={{ height: 96 }} />
        </div>
      </Scroll>
      <Cta>
        {step < 3 ? (
          <button type="button" className="btn btn-primary" disabled={!canNext}
                  onClick={() => setStep(step + 1)}>다음</button>
        ) : (
          <button type="button" className="btn btn-primary" disabled={busy}
                  onClick={start}>{busy ? '만드는 중…' : '이걸로 할게요'}</button>
        )}
      </Cta>
    </Screen>
  );
}

/** 이모지 고르기는 목록을 띄울 만큼 무거운 선택이 아니다 — 눌러서 돌린다. */
const EMOJIS = ['🎯', '✈️', '💻', '📱', '🏠', '🚗', '🎓', '💍', '🎮', '📷', '🛟', '🎁', '⌚'];
function nextEmoji(cur: string) {
  const i = EMOJIS.indexOf(cur);
  return EMOJIS[(i + 1) % EMOJIS.length];
}
