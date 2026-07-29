/**
 * 한 달 완료 축하 (개편안 `s-monthend`) — 결산으로 넘어가기 전 한 박자.
 *
 * <p><b>왜 숫자 없이 한 화면을 쓰는가.</b> 30일을 버틴 직후에 바로 성적표를 들이밀면, 잘한 달은
 * 당연해지고 못한 달은 질책이 된다. 먼저 "수고했다"만 말하고, 셈은 다음 화면에서 한다.
 *
 * <p>박수 손·색종이·반짝임은 개편안의 CSS 애니메이션(`clapL`/`clapR`·`fall`·`sparkle`)을 그대로
 * 쓴다. 자바스크립트 없이 CSS만으로 돌아 화면이 가볍다.
 */
import { Screen } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';

/** 색종이 6장 — 색·낙하시간·시작지연을 달리해 한꺼번에 떨어지지 않게 한다. */
const CONFETTI = [
  { left: '12%', background: '#5FA5F9', animationDuration: '2.8s', animationDelay: '0s' },
  { left: '26%', background: '#F2B84B', animationDuration: '3.4s', animationDelay: '.6s' },
  { left: '41%', background: '#34C38F', animationDuration: '2.6s', animationDelay: '1.1s' },
  { left: '58%', background: '#F06292', animationDuration: '3.1s', animationDelay: '.3s' },
  { left: '73%', background: '#8B5CF6', animationDuration: '2.9s', animationDelay: '.9s' },
  { left: '87%', background: '#FFD34E', animationDuration: '3.6s', animationDelay: '1.4s' },
];

export function MonthEnd() {
  const { go } = useSession();
  const { home } = useGuardian();

  // 몇 월 챌린지였는지는 서버가 준 종료일에서 읽는다 — 브라우저 시계를 쓰면 데모에서 어긋난다.
  const end = home?.challenge?.endDate;
  const month = end ? Number(end.slice(5, 7)) : null;
  const days = home?.challenge?.daysTotal ?? null;

  return (
    <Screen title="한 달 완료" background="linear-gradient(160deg,#E7F4DC,#FFFFFF)">
      {CONFETTI.map((s, i) => (
        <div className="confetti" key={i} style={s} />
      ))}
      <div className="done-hero">
        <div className="clap-stage">
          <span className="spark sp1" />
          <span className="spark sp2" />
          <span className="spark sp3" />
          <svg className="hand hand-l" viewBox="0 0 74 96"><use href="#i-hand" /></svg>
          <svg className="hand hand-r" viewBox="0 0 74 96"><use href="#i-hand" /></svg>
        </div>
        <div style={{ fontSize: 24, fontWeight: 700 }}>한 달 동안 수고했어요!</div>
        <p style={{ fontSize: 15, color: 'var(--t2)', lineHeight: 1.5, margin: 0 }}>
          {month ? `${month}월 챌린지 ${days ?? 30}일을 완주했어요.` : '이번 챌린지를 완주했어요.'}
          <br />
          잘 지킨 날도, 무너진 날도 전부 의미가 있었어요.
        </p>
      </div>
      <div style={{ padding: '0 24px 40px' }}>
        <button className="btn btn-primary" onClick={() => go('settle')}>다음</button>
      </div>
    </Screen>
  );
}
