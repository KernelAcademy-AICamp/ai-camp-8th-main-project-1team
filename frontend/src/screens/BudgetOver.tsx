/**
 * 예외 1. 저금통 초과 (프로토타입_0818 `s-over`) — 0818 신설 화면.
 *
 * <h2>왜 이 화면이 필요한가</h2>
 *
 * <p>예산을 넘긴 순간 앱이 할 수 있는 가장 나쁜 일은 <b>실패를 선고하는 것</b>이다. 그러면
 * 사람은 앱을 지운다. 이 화면은 같은 사실을 다르게 배치한다 —
 *
 * <ol>
 *   <li>넘긴 것은 맞다고 <b>먼저</b> 인정한다(숨기면 신뢰를 잃는다)</li>
 *   <li>그런데 <b>지킨 돈은 0이 아니다</b>. 그 숫자를 크게 둔다</li>
 *   <li>저금통은 <b>따로따로</b>다 — 하나가 넘쳐도 나머지는 계속 간다</li>
 *   <li>남은 날에 할 수 있는 선택 셋을 준다. 무엇을 골라도 챌린지는 이어진다</li>
 * </ol>
 *
 * <p><b>'실패'라는 낱말을 쓰지 않는다</b>(기획 §5.1.5). 예산을 넘긴 것은 사실이고, 실패는
 * 판정이다. 이 앱은 사실만 말한다.
 */
import { useState } from 'react';
import { AppBar, Scroll, Screen, Loading } from '../components/ui';
import { Icon } from '../components/Icons';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { won, iconOf } from '../lib/format';

export function BudgetOver() {
  const { back, go, userId } = useSession();
  const { home } = useGuardian();
  const [toast, setToast] = useState<string | null>(null);
  void userId;

  if (!home) {
    return (
      <Screen id="over" title="저금통">
        <AppBar onBack={back} title="저금통" />
        <div className="pad"><Loading label="저금통을 불러오는 중" rows={5} /></div>
      </Screen>
    );
  }

  const ch = home.challenge;
  const rows = ch.categorySpend ?? [];
  /** 넘친 저금통 — 예산(cap)을 쓴 돈이 넘어선 곳. 여러 개면 가장 많이 넘긴 곳을 머리에 세운다. */
  const over = rows.filter((c) => c.cap > 0 && c.spent > c.cap)
    .slice().sort((a, b) => (b.spent - b.cap) - (a.spent - a.cap));
  const head = over[0];
  /** 아직 멀쩡한 저금통 — "따로따로"를 눈으로 보이는 자리다. */
  const safe = rows.filter((c) => c.cap > 0 && c.spent <= c.cap);

  const title = head ? `${head.label} 저금통` : '저금통';
  const dday = ch.daysLeft > 0 ? `D-${ch.daysLeft}` : '마지막 날';

  return (
    <Screen id="over" title={title}>
      <AppBar onBack={back} title={title} steps={dday} />
      <Scroll><div className="pad">
        <div className="h-title">
          예산은 넘었지만,<br />{won(ch.securedSaving)}은 지켜냈어요
        </div>
        <div className="h-sub">
          {head
            ? <>{head.label} 예산 {won(head.cap)}을 넘었어요. 그래도 챌린지는 끝나지 않아요.</>
            : <>예산을 넘긴 저금통이 있어요. 그래도 챌린지는 끝나지 않아요.</>}
        </div>

        {/* **0원이 아니다**를 가장 크게. 넘긴 사실보다 이 숫자가 커야 한다. */}
        <div className="hero" style={{ marginTop: 4 }}>
          <div className="cap">지금까지 지킨 돈</div>
          <div className="big">{won(ch.securedSaving)}</div>
          <div className="sub" style={{ fontSize: 13 }}>
            0원이 아니에요.
            {ch.daysLeft > 0 ? <> 남은 {ch.daysLeft}일 동안 더 쌓을 수 있어요.</> : <> 이번 회차는 여기까지예요.</>}
          </div>
        </div>

        {/* 지킴이가 본 사실 하나 — 판단이 아니라 <b>셀 수 있는 사실</b>만 말한다. */}
        <div className="label">지킴이가 본 사실 하나</div>
        <div className="card" style={{ padding: '16px 20px' }}>
          <p style={{ margin: 0, fontSize: 14, lineHeight: 1.5, color: 'var(--t2)', fontWeight: 500 }}>
            {home.oneline?.text ?? '이번 회차의 소비를 계속 지켜보고 있어요.'}
          </p>
        </div>

        {safe.length > 0 && (
          <>
            <div className="label">다른 저금통은 그대로예요</div>
            <div className="card" style={{ padding: '8px 20px' }}>
              {safe.map((c, i) => {
                const { icon, bg } = iconOf(c.label);
                return (
                  <div key={c.code}>
                    {i > 0 && <div className="divider" />}
                    <div className="list-item">
                      <span className="ic" style={{ background: bg }}><Icon id={icon} /></span>
                      <div className="tx">
                        <b>{c.label} 저금통</b>
                        <span>예산 {won(c.cap)} 중 {won(c.spent)} 사용</span>
                      </div>
                      <span className="tag-good">안전</span>
                    </div>
                  </div>
                );
              })}
            </div>
            <div className="pv">
              저금통은 따로따로예요. {head ? `${head.label}이` : '하나가'} 넘쳐도 나머지는 계속 이어져요.
            </div>
          </>
        )}

        <div className="label">
          {ch.daysLeft > 0 ? `남은 ${ch.daysLeft}일, 어떻게 할까요?` : '이제 어떻게 할까요?'}
        </div>
        <div className="card" style={{ padding: '8px 20px' }}>
          <button type="button" className="list-item" onClick={() => go('ob')}>
            <div className="tx">
              <b>목표 다시 잡기</b>
              <span>남은 기간 기준으로 예산을 다시 계산해요</span>
            </div>
            <span className="arrow" aria-hidden="true">›</span>
          </button>
          <div className="divider" />
          {/* 주말 미니 챌린지는 서버에 아직 없다 — <b>있는 척하지 않고</b> 준비 중이라고 말한다.
              버튼을 지우면 디자인이 비고, 눌러서 아무 일도 없으면 고장으로 보인다. */}
          <button type="button" className="list-item"
            onClick={() => setToast('주말 미니 챌린지는 준비 중이에요')}>
            <div className="tx">
              <b>주말 미니 챌린지</b>
              <span>이번 주말만 지키는 작은 목표로 바꿔요</span>
            </div>
            <span className="arrow" aria-hidden="true">›</span>
          </button>
          <div className="divider" />
          <button type="button" className="list-item" onClick={() => go('home')}>
            <div className="tx">
              <b>이대로 계속 볼게요</b>
              <span>기록은 계속 쌓이고, 다음 정산 때 다시 물어봐요</span>
            </div>
            <span className="arrow" aria-hidden="true">›</span>
          </button>
        </div>
        <div className="pv">무엇을 골라도 챌린지는 이어져요. 방과 소품, 포인트도 그대로예요.</div>
        <div className="spacer" style={{ height: 20 }} />
      </div></Scroll>
      {toast && <div className="mini-toast show" role="status">{toast}</div>}
    </Screen>
  );
}
