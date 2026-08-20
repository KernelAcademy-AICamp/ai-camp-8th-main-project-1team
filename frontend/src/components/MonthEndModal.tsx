/**
 * 한 달 완료 축하 — <b>화면이 아니라 모달</b> (프로토타입_0818 `#monthModal`).
 *
 * <p><b>0818 에서 화면(`s-monthend`)이 사라지고 모달이 됐다.</b> 축하는 <b>가는 길</b>이지
 * 머무는 곳이 아니다. 전체 화면으로 두면 뒤로가기 대상이 되고("이미 본 축하로 되돌아감"),
 * 홈에서 무슨 일이 있었는지 맥락도 끊긴다. 홈 위에 덮이면 닫는 순간 원래 자리로 돌아온다.
 *
 * <p>폭죽은 <b>여덟 조각이 각자 다른 방향·회전·지연</b>으로 터진다. 방향값은 인라인 CSS 변수로
 * 넣는다(`--dx`·`--dy`·`--rot`) — 조각마다 규칙이 다르면 클래스로는 못 적는다.
 * 기본값은 `app.css` 의 `.pop` 에 있어 값이 안 와도 규칙이 죽지 않는다.
 *
 * <p><b>닫는 길이 둘이다.</b> '다음 목표 설정하기'로 결산으로 가거나, '나중에 볼게요'로 홈에
 * 남는다. 축하를 본 사실은 부르는 쪽이 기억한다 — 다시 열 때마다 또 터지면 축하가 아니라 방해다.
 */
import { Icon } from './Icons';
import { won } from '../lib/format';

/** 조각마다 다른 방향·회전·지연 — 프로토타입 실측값 그대로. */
const POPS = [
  { dx: -86, dy: -69, rot: 200, color: '#F06292', delay: 0 },
  { dx: -46, dy: -98, rot: 160, color: '#FFD34E', delay: 0.12 },
  { dx: 23, dy: -104, rot: 220, color: '#34C38F', delay: 0.05 },
  { dx: 75, dy: -81, rot: 180, color: '#5FA5F9', delay: 0.18 },
  { dx: 98, dy: -17, rot: 240, color: '#8B5CF6', delay: 0.08 },
  { dx: -98, dy: -12, rot: 150, color: '#F2B84B', delay: 0.22 },
  { dx: 81, dy: 40, rot: 170, color: '#F06292', delay: 0.26 },
  { dx: -78, dy: 46, rot: 190, color: '#5FA5F9', delay: 0.3 },
];

/** 박수 일러스트 — 앞뒤 손이 1.25초 주기로 어긋나게 움직인다(프로토타입 원본 path). */
const Clap = () => (
  <svg className="mm-ilu mm-clap" viewBox="0 0 40 40" role="img" aria-label="박수">
    <g className="hb"><path d="m29.014,8.111c-1.546,0-2.799,1.253-2.799,2.799l-.742,4.406-3.224-3.224h0s-6.07-6.07-6.07-6.07c0,0-.001-.001-.002-.002-1.072-1.073-2.792-1.091-3.841-.042-1.049,1.049-1.03,2.769.042,3.841h0s0,0,0,0l6.072,6.072-.008.009-7.74-7.74c-1.071-1.072-2.79-1.09-3.838-.042-1.048,1.048-1.029,2.766.042,3.838l7.74,7.74-.012.012-6.609-6.609c-1.064-1.064-2.788-1.066-3.851-.003-1.062,1.062-1.061,2.787.003,3.851l2.02,2.02c-.943-.615-2.202-.524-3.01.284-.932.932-.913,2.462.043,3.417h0s8.72,8.72,8.72,8.72h0c4.544,4.544,11.912,4.544,16.456,0,2.272-2.272,3.408-5.25,3.408-8.228v-12.25c0-1.546-1.253-2.799-2.799-2.799Z" fill="#e68e51" /></g>
    <g className="hf"><path d="m32.877,9.057c-1.449,0-2.623,1.175-2.623,2.624l-.682,4.048-3.636-3.636h0s-6.087-6.087-6.087-6.087c0,0,0-.001-.001-.002-.918-.917-2.389-.934-3.287-.036-.898.898-.881,2.369.036,3.287h0,0s6.089,6.089,6.089,6.089l-.595.594-7.763-7.763c-.922-.922-2.401-.938-3.303-.036-.902.902-.886,2.381.036,3.303l7.763,7.763-.592.592-6.647-6.647c-.901-.901-2.36-.902-3.259-.003-.899.899-.898,2.358.003,3.259l6.647,6.647-.613.614-4.015-4.015h0c-.913-.914-2.375-.932-3.265-.042-.89.89-.872,2.352.041,3.265l4.016,4.016h0l1.513,1.513,2.802,2.802h0l.183.183c4.544,4.544,11.912,4.544,16.456,0,2.272-2.272,3.408-5.25,3.408-8.228v-11.48c0-1.449-1.175-2.624-2.623-2.624Z" fill="#ffcca8" /></g>
  </svg>
);

export function MonthEndModal({ open, label, secured, onNext, onClose }: {
  open: boolean;
  /** "7월" 같은 회차 이름. */
  label: string;
  /** 그 회차에 지켜낸 돈. */
  secured: number;
  onNext: () => void;
  onClose: () => void;
}) {
  if (!open) return null;
  return (
    <div className={`month-modal${open ? ' on' : ''}`} role="dialog" aria-modal="true"
      aria-label={`${label} 챌린지 종료`}>
      <button type="button" className="mm-x" onClick={onClose} aria-label="닫기">
        <Icon id="i-x" className="ci" size={22} />
      </button>
      <div className="mm-head">
        <span className="mm-cap">{label} 챌린지 종료</span>
        <b className="mm-title"><em>{won(secured)}을</em> 지켜냈어요</b>
        <span className="mm-sub">한 달 동안 수고했어요!</span>
      </div>
      <div className="mm-body">
        {POPS.map((p, i) => (
          <span className="mm-pop" key={i} aria-hidden="true"
            style={{
              ['--dx' as string]: `${p.dx}px`,
              ['--dy' as string]: `${p.dy}px`,
              ['--rot' as string]: `${p.rot}deg`,
              background: p.color,
              animationDelay: `${p.delay}s`,
            }} />
        ))}
        <Clap />
      </div>
      <div>
        <button type="button" className="btn btn-primary" onClick={onNext}>다음 목표 설정하기</button>
        <button type="button" className="mm-later" onClick={onClose}>나중에 볼게요</button>
      </div>
    </div>
  );
}
