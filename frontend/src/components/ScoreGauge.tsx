/**
 * 소비 건강 점수 게이지 — 등급 색은 MOA 토큰(초록·파랑·주황·빨강)으로 맞췄다.
 * 점수·등급 계산은 서버(`/api/score`)가 한다.
 */
const GRADE_COLOR: Record<string, string> = {
  A: 'var(--green)', B: 'var(--blue)', C: 'var(--amber)', D: 'var(--red)',
};

export function ScoreGauge({ score, grade }: { score: number; grade: string }) {
  const r = 42, C = 2 * Math.PI * r;
  const offset = C * (1 - Math.max(0, Math.min(100, score)) / 100);
  const color = GRADE_COLOR[grade] ?? 'var(--blue)';
  return (
    <div style={{ position: 'relative', width: 132, flex: '0 0 auto' }}
      role="img" aria-label={`소비 건강 점수 ${score}점, ${grade}등급`}>
      <svg viewBox="0 0 100 100" style={{ width: '100%', display: 'block', transform: 'rotate(-90deg)' }} aria-hidden="true">
        <circle cx="50" cy="50" r={r} fill="none" stroke="var(--line)" strokeWidth="9" />
        <circle cx="50" cy="50" r={r} fill="none" stroke={color} strokeWidth="9" strokeLinecap="round"
          strokeDasharray={C} strokeDashoffset={offset} />
      </svg>
      <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 2 }} aria-hidden="true">
        <div style={{ fontSize: 28, fontWeight: 800, letterSpacing: '-1px' }}>
          {score}<small style={{ fontSize: 13, fontWeight: 700, marginLeft: 1 }}>점</small>
        </div>
        <span className="mchip" style={{ background: 'var(--bg)', color }}>{grade}등급</span>
      </div>
    </div>
  );
}

/** 점수 구성요소 — 이름·막대·비율을 함께 적어 색만으로 읽지 않게 한다. */
export function Factor({ label, value }: { label: string; value: number }) {
  const percent = Math.round(Math.min(1, Math.max(0, value)) * 100);
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 13 }}>
      <span style={{ width: 84, flex: '0 0 auto', color: 'var(--t2)' }}>{label}</span>
      <span className="bar" style={{ flex: 1, margin: 0 }} aria-hidden="true">
        <i style={{ width: `${percent}%`, background: 'var(--blue)' }} />
      </span>
      <span className="num" style={{ width: 38, textAlign: 'right', fontWeight: 700 }}>{percent}%</span>
    </div>
  );
}
