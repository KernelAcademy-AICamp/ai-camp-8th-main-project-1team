/**
 * 추가 — 소비를 적고, 그것이 낭비였는지 답한다.
 *
 * <h2>왜 여러 걸음인가</h2>
 *
 * <b>프로토타입에는 이 화면이 없다.</b> 본 서비스는 마이데이터가 결제를 실어 오므로 사람이
 * 적을 일이 없고, 그래서 27개 화면 어디에도 소비를 적는 자리가 없다. 베낄 원본이 없다고
 * 제 마음대로 지으면 그 화면만 다른 앱처럼 보인다 — 그래서 <b>가장 가까운 입력 흐름</b>인
 * 목표 정하기(`s-goal1`~`s-goal3`)의 뼈대를 그대로 따랐다.
 *
 * <pre>
 *   걸음 하나에 물음 하나        `.h-title` 한 줄
 *   글자는 밑줄 입력            `.gs-field`
 *   금액은 큰 숫자 + 키패드      `.gs-amt` + `AmountKeypad` (기기 자판을 안 띄운다)
 *   고르는 것은 칩              `.gs-chips`
 *   다음은 바닥 고정 버튼        `.cta-fixed`
 * </pre>
 *
 * <h2>마지막 걸음이 되묻기다</h2>
 *
 * 넣고 나서 <b>화면이 바뀌며</b> 카드 덱이 뜬다. 폼 위에 얹어 두면 방금 넣은 것이
 * 무엇이었는지 알 수 없고, 폼과 물음이 같은 화면에서 섞인다.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { AppBar, Scroll, Screen, ErrorBox } from '../components/ui';
import { AmountKeypad } from '../components/AmountKeypad';
import { SpendDeck } from '../components/SpendDeck';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api, catLabel, type PendingVerdict } from '../lib/api';

const pad = (n: number) => String(n).padStart(2, '0');
const DOW = ['일', '월', '화', '수', '목', '금', '토'];

/** 최근 7일 — 가계부에 적을 일이 있는 범위다. 그보다 오래된 것은 잘 안 적는다. */
function recentDays() {
  const now = new Date();
  return Array.from({ length: 7 }, (_, i) => {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i);
    return {
      value: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`,
      label: i === 0 ? '오늘' : i === 1 ? '어제' : `${d.getMonth() + 1}.${d.getDate()}(${DOW[d.getDay()]})`,
    };
  });
}

const STEPS = ['어디에서 썼나요?', '얼마를 썼나요?', '언제 썼나요?'];

export function MyRecord() {
  const { userId, go } = useSession();
  /**
   * 넣은 뒤 <b>홈이 읽는 것을 다시 받게 한다.</b> 지킴이 상태는 앱을 열 때 한 번 받고
   * 60초마다 다시 받는데, 그 사이에 홈으로 가면 <b>넣기 전 숫자</b>가 그대로 보인다 —
   * 방금 넣은 소비가 0원으로 나오는 것이 그것이다.
   */
  const { reload: reloadGuardian } = useGuardian();
  const days = useMemo(recentDays, []);
  const pending = useAsync(() => api.pendingVerdicts().catch(() => []), []);

  const [step, setStep] = useState(0);
  const [merchant, setMerchant] = useState('');
  const [amount, setAmount] = useState(0);
  const [day, setDay] = useState(days[0].value);
  const [hour, setHour] = useState(() => pad(new Date().getHours()));
  const [minute, setMinute] = useState(() => pad(Math.floor(new Date().getMinutes() / 5) * 5));
  const [found, setFound] = useState<{ category2: string | null; category3: string | null } | null>(null);
  const [error, setError] = useState<unknown>(null);
  /**
   * 방금 넣은 <b>그 한 건</b>의 열쇠.
   *
   * <b>답하거나 건너뛰면 홈으로 간다.</b> 빈 폼으로 돌아오면 방금 한 일이 어디로 갔는지
   * 알 수 없고, 또 넣으라는 말처럼 보인다. 넣은 결과는 홈에 있다.
   *
   * 처음에는 아직 답하지 않은 것을 스무 장까지 몰아 보여줬는데, 하나 넣고 스무 번 넘기게 하는
   * 것은 넣는 사람이 기대한 일이 아니다. 넣은 것만 묻는다.
   */
  const [asking, setAsking] = useState<PendingVerdict | null>(null);
  /** 금액 글자를 그대로 그려 폭을 재는 자리 — 화면에는 안 보인다. */
  const ghostRef = useRef<HTMLSpanElement>(null);
  const amountRef = useRef<HTMLInputElement>(null);

  /**
   * 타이핑이 멎으면 무엇으로 잡히는지 물어본다. 한 글자마다 부르면 `스`·`스타`·`스타벅`이
   * 차례로 다른 답을 내며 화면이 요동친다. 250ms 는 글자 사이보다 길고 사람이 멈춤을
   * 느끼기에는 짧다.
   */
  useEffect(() => {
    const name = merchant.trim();
    if (!name) { setFound(null); return; }
    let alive = true;
    const t = window.setTimeout(() => {
      void api.previewCategory(name).then((r) => { if (alive) setFound(r); })
        .catch(() => { if (alive) setFound(null); });
    }, 250);
    return () => { alive = false; window.clearTimeout(t); };
  }, [merchant]);

  /**
   * 금액 칸을 <b>글자 폭에 딱 맞춘다.</b>
   *
   * 입력칸은 폭을 안 주면 <b>기본 20글자</b>다 — 32px 글씨로 373px 이라, `3,300` 을 쳐도
   * 칸이 그만큼 벌어져 `원` 이 저 멀리 떨어진다. `size` 속성도 <i>평균</i> 글자폭이라
   * 쉼표가 섞이면 남는다. 같은 글꼴로 한 번 그려서 재면 어긋날 자리가 없다.
   */
  useEffect(() => {
    const g = ghostRef.current, i = amountRef.current;
    if (g && i) i.style.width = `${Math.ceil(g.getBoundingClientRect().width)}px`;
  });

  const cat = found?.category2 ?? null;

  async function submit() {
    setError(null);
    // 아래에서 입력칸을 비우므로 값을 먼저 붙들어 둔다.
    const name = merchant.trim();
    try {
      const r = await api.addConsumption({
        userId,
        // 못 찾았으면 덱에서 묻기 전에 카테고리가 있어야 하므로 모르는 칸으로 넣는다.
        categoryCode: cat ?? '카테고리없음',
        amount,
        occurredAt: `${day}T${hour}:${minute}:00`,
        planned: true,
        merchantName: name,
      });
      setMerchant(''); setAmount(0); setFound(null); setStep(0);
      setDay(days[0].value);
      /*
       * <b>되받아오길 기다리지 않는다.</b> 예전에는 `paymentId` 만 들고 목록을 다시 받아
       * 거기서 찾았는데, 다시 받기 전에 화면이 먼저 그려져 <b>"답할 소비가 없어요"</b> 가
       * 스쳤다. 넣은 값은 이미 우리 손에 있으므로 그것으로 바로 카드를 세운다.
       */
      setAsking({
        paymentId: r.paymentId ?? '',
        merchant: name,
        amount,
        date: day,
        time: `${hour}:${minute}`,
        category2: r.category2 ?? cat ?? '카테고리없음',
        waste: r.flagged ?? false,
        reason: r.reason ?? null,
      });
      pending.reload();
      void reloadGuardian();
    } catch (e) { setError(e); }
  }

  /* ── 되묻기 걸음 ─────────────────────────────────────── */
  if (asking) {
    const items = [asking];
    return (
      <Screen title="소비 라벨" hasTabBar className="white">
        <AppBar title="소비 라벨" action={
          <button type="button" className="dk-undo" style={{ marginLeft: 'auto', paddingRight: 16 }}
            onClick={() => { void reloadGuardian(); setAsking(null); go('home'); }}>건너뛰기</button>} />
        <Scroll><div className="pad">
          <h2 className="h-title">이 소비,<br />필요했나요?</h2>
          {items.length === 0
            ? <p className="empty">답할 소비가 없어요.</p>
            : <SpendDeck items={items}
                onDone={() => { void reloadGuardian(); setAsking(null); go('home'); }} />}
        </div></Scroll>
      </Screen>
    );
  }

  /* ── 적는 걸음 ───────────────────────────────────────── */
  const ready = step === 0 ? merchant.trim() !== ''
    : step === 1 ? amount > 0
    : true;

  return (
    <Screen title="추가" hasTabBar className="white">
      {/* <b>자체 뒤로가기를 두지 않는다.</b> 검수 기준이 "토스 네비게이션 바의 뒤로가기와
          미니앱이 만든 뒤로가기가 동시에 보이지 않아야 한다"고 못박는다 — 화살표를 그리면
          토스 바의 `‹` 와 나란히 서서 어느 것이 무엇인지 알 수 없다.
          걸음을 되짚는 길은 아래 <b>적어 둔 값</b>을 누르는 것으로 연다. */}
      <AppBar title="소비 추가" steps={`${step + 1}/3`} />
      <Scroll>
        <div className="pad">
          {/* 지나온 걸음에 적은 값 — 누르면 그 걸음으로 돌아간다. 뒤로가기가 아니라
              <b>직접 이동</b>이라 어디로 가는지 글자가 말해 준다. */}
          {step > 0 && (
            <div className="steps-done">
              <button type="button" onClick={() => setStep(0)}>
                {merchant.trim()}<span aria-hidden="true"> ✎</span>
              </button>
              {step > 1 && (
                <button type="button" onClick={() => setStep(1)}>
                  {amount.toLocaleString('ko-KR')}원<span aria-hidden="true"> ✎</span>
                </button>
              )}
            </div>
          )}

          <h2 className="h-title">{STEPS[step]}</h2>
          <ErrorBox error={error} />

          {step === 0 && (
            <>
              {/* `.gs-field` 는 본 서비스에서 <b>감싸는 상자</b>다(이모지 + 입력).
                  프로토타입은 입력 자체였는데, 화면들이 이미 이 모양으로 서 있으므로
                  여기만 다르게 두면 그 칸만 어긋난다. */}
              <div className="gs-field">
                <input className="inp" value={merchant} maxLength={40} autoComplete="off"
                  /* 자리표시에 <b>가게 이름을 통째로 쓰지 않는다.</b> 회색이어도
                     `스타벅스 상암DMC점` 처럼 그럴듯한 값이면 이미 적힌 것으로 읽힌다.
                     본 서비스가 같은 칸에 쓰는 말을 그대로 쓴다. */
                  placeholder="가맹점 이름"
                  onChange={(e) => setMerchant(e.target.value)} />
              </div>
              {merchant.trim() !== '' && (
                <p className="empty" style={{ marginTop: 4 }}>
                  {cat
                    ? <><b style={{ color: 'var(--blue-t)' }}>{catLabel(cat)}</b>
                        {found?.category3 ? ` · ${found.category3}` : ''}로 넣을게요</>
                    : '처음 보는 가게예요'}
                </p>
              )}
            </>
          )}

          {step === 1 && (
            <>
              {/* <b>눌러서 자판으로도 친다.</b> 키패드만 두면 긴 금액을 한 자리씩 눌러야 하고,
                  자판만 두면 기기마다 다른 자판이 뜬다. 둘 다 같은 값을 고친다. */}
              <div className={amount > 0 ? 'gs-amt' : 'gs-amt empty'}>
                <input
                  ref={amountRef}
                  className="gs-amt-in num" inputMode="numeric" aria-label="금액"
                  value={amount > 0 ? amount.toLocaleString('ko-KR') : ''} placeholder="0"
                  onChange={(e) => {
                    const digits = e.target.value.replace(/[^0-9]/g, '').slice(0, 10);
                    setAmount(digits ? Number(digits) : 0);
                  }} />
                <span ref={ghostRef} className="gs-amt-ghost num" aria-hidden="true">
                  {amount > 0 ? amount.toLocaleString('ko-KR') : '0'}
                </span>
                <span className="gs-amt-won">원</span>
                {amount > 0 && (
                  <button type="button" className="clr" aria-label="금액 비우기"
                    onClick={() => setAmount(0)}>✕</button>
                )}
              </div>

            </>
          )}

          {step === 2 && (
            <>
              <div className="gs-chips">
                {days.map((d) => (
                  <button key={d.value} type="button" className={day === d.value ? 'on' : undefined}
                    onClick={() => setDay(d.value)}>{d.label}</button>
                ))}
              </div>
              {/* 달력을 열지 않는다 — 오늘 산 커피를 적는데 달력에서 오늘을 찾는 것은 손이 는다. */}
              <div className="gs-chips" style={{ marginTop: 24 }}>
                <select className="inp" value={hour} onChange={(e) => setHour(e.target.value)}
                  aria-label="시" style={{ flex: 1 }}>
                  {Array.from({ length: 24 }, (_, h) => pad(h))
                    .map((h) => <option key={h} value={h}>{Number(h)}시</option>)}
                </select>
                <select className="inp" value={minute} onChange={(e) => setMinute(e.target.value)}
                  aria-label="분" style={{ flex: 1 }}>
                  {Array.from({ length: 12 }, (_, i) => pad(i * 5))
                    .map((m) => <option key={m} value={m}>{Number(m)}분</option>)}
                </select>
              </div>
            </>
          )}

          <div className="spacer" />
        </div>
      </Scroll>

      {/* <b>키패드는 화면 아래에 고정한다.</b> 스크롤 안에 두었더니 화면이 짧은 기기에서
          아래 두 줄이 하단 버튼과 탭바에 가려 `1`~`6` 까지만 보였다. 프로토타입도 금액 걸음의
          키패드를 `.gs-bottom` 으로 바닥에 붙이고 버튼을 그 안에 넣는다 — 위쪽은 금액만
          보여주면 되므로 어떤 기기에서도 잘릴 자리가 없다. */}
      {step === 1 ? (
        <div className="amt-bottom">
          <AmountKeypad value={amount} onChange={setAmount}
            quickAdds={[1000, 5000, 10000, 50000]} />
          <button className="btn btn-primary" disabled={!ready}
            style={{ marginTop: 10 }} onClick={() => setStep(2)}>다음</button>
        </div>
      ) : (
        <div className="cta-fixed">
          <button className="btn btn-primary" disabled={!ready}
            onClick={() => (step < 2 ? setStep(step + 1) : void submit())}>
            {step < 2 ? '다음' : '추가'}
          </button>
        </div>
      )}
    </Screen>
  );
}
