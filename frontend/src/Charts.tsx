/**
 * 소비 분석 차트 (§13-6) — 라이브러리 없이 순수 SVG로 그린다.
 * 도넛(카테고리 구성) · 바(월별 지출). 카테고리 색은 팔레트에서 순환한다.
 */

const PALETTE = ['#191BA9', '#5CC2F2', '#27C827', '#ED903B', '#B339B3', '#EF3333', '#E8B13C', '#6BBEFF'];

export function categoryColor(index: number): string {
  return PALETTE[index % PALETTE.length];
}

const won = (n: number) => Math.round(n).toLocaleString('ko-KR') + '원';

/** 도넛 차트 — 카테고리별 지출 구성. slices: {label, value} 배열. */
export function DonutChart({ slices, centerLabel }: { slices: { label: string; value: number }[]; centerLabel?: string }) {
  const total = slices.reduce((sum, slice) => sum + slice.value, 0);
  const size = 180, stroke = 26, radius = (size - stroke) / 2, circumference = 2 * Math.PI * radius;
  let offset = 0;
  const desc = slices.map((slice) => `${slice.label} ${Math.round((slice.value / (total || 1)) * 100)}%`).join(', ');
  return (
    <div className="donut" role="img" aria-label={`카테고리 소비 구성: ${desc}`}>
      <svg viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
        <circle cx={size / 2} cy={size / 2} r={radius} fill="none" stroke="var(--surface-2)" strokeWidth={stroke} />
        {slices.map((slice, index) => {
          const fraction = total > 0 ? slice.value / total : 0;
          const dash = fraction * circumference;
          const segment = (
            <circle key={slice.label} cx={size / 2} cy={size / 2} r={radius} fill="none"
              stroke={categoryColor(index)} strokeWidth={stroke}
              strokeDasharray={`${dash} ${circumference - dash}`}
              strokeDashoffset={-offset}
              transform={`rotate(-90 ${size / 2} ${size / 2})`} />
          );
          offset += dash;
          return segment;
        })}
      </svg>
      {centerLabel && <div className="donut-center" aria-hidden="true">{centerLabel}</div>}
    </div>
  );
}

/** 도넛 범례. */
export function DonutLegend({ slices }: { slices: { label: string; value: number }[] }) {
  const total = slices.reduce((sum, slice) => sum + slice.value, 0) || 1;
  return (
    <ul className="donut-legend">
      {slices.map((slice, index) => (
        <li key={slice.label}>
          <span className="dl-dot" style={{ background: categoryColor(index) }} aria-hidden="true" />
          <span className="dl-label">{slice.label}</span>
          <span className="dl-val">{won(slice.value)} <em>({Math.round((slice.value / total) * 100)}%)</em></span>
        </li>
      ))}
    </ul>
  );
}

/** 바 차트 — 월별 지출. bars: {label, value} 배열(시간순). */
export function BarChart({ bars }: { bars: { label: string; value: number }[] }) {
  const max = Math.max(1, ...bars.map((bar) => bar.value));
  const desc = bars.map((bar) => `${bar.label} ${won(bar.value)}`).join(', ');
  return (
    <div className="barc" role="img" aria-label={`월별 지출: ${desc}`}>
      {bars.map((bar) => (
        <div className="barc-col" key={bar.label} title={`${bar.label} · ${won(bar.value)}`}>
          <span className="barc-bar" style={{ height: `${(bar.value / max) * 100}%` }} aria-hidden="true" />
          <span className="barc-lb" aria-hidden="true">{bar.label}</span>
        </div>
      ))}
    </div>
  );
}
