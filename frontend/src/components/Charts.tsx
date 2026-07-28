/**
 * 소비 분석 차트 — 라이브러리 없이 순수 SVG. 도넛(카테고리 구성) · 바(월별·요일·시간대).
 * 팔레트는 MOA 아이콘 색에서 뽑아 같은 톤을 쓰고, 인접 색끼리 명도를 벌려 흑백에서도 구분된다.
 * 색만으로 정보를 전달하지 않도록 범례에 이름·금액·비율을 함께 적는다(KWCAG 5.4.1 색에 무관한 인식).
 */
import { won } from '../lib/format';

const PALETTE = ['#3182F6', '#8B5CF6', '#03B26C', '#FF9500', '#F06292', '#5FA5F9', '#F2B84B', '#3D4654'];

export function categoryColor(index: number): string {
  return PALETTE[index % PALETTE.length];
}

export interface Slice { label: string; value: number }

/** 도넛 차트 — 카테고리별 지출 구성. */
export function DonutChart({ slices, centerLabel }: { slices: Slice[]; centerLabel?: string }) {
  const total = slices.reduce((sum, s) => sum + s.value, 0);
  const size = 170, stroke = 26, radius = (size - stroke) / 2, circumference = 2 * Math.PI * radius;
  let offset = 0;
  const desc = slices.map((s) => `${s.label} ${Math.round((s.value / (total || 1)) * 100)}%`).join(', ');
  return (
    <div className="donut" role="img" aria-label={`카테고리 소비 구성: ${desc}`}>
      <svg viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
        <circle cx={size / 2} cy={size / 2} r={radius} fill="none" stroke="var(--line)" strokeWidth={stroke} />
        {slices.map((s, index) => {
          const fraction = total > 0 ? s.value / total : 0;
          const dash = fraction * circumference;
          const segment = (
            <circle key={s.label} cx={size / 2} cy={size / 2} r={radius} fill="none"
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
export function DonutLegend({ slices }: { slices: Slice[] }) {
  const total = slices.reduce((sum, s) => sum + s.value, 0) || 1;
  return (
    <ul className="donut-legend">
      {slices.map((s, index) => (
        <li key={s.label}>
          <span className="dl-dot" style={{ background: categoryColor(index) }} aria-hidden="true" />
          <span className="dl-label">{s.label}</span>
          <span className="dl-val">{won(s.value)} <em>({Math.round((s.value / total) * 100)}%)</em></span>
        </li>
      ))}
    </ul>
  );
}

/** 바 차트 — 월별·요일별·시간대별 지출. color로 계열을 구분한다. */
export function BarChart({ bars, color = 'var(--blue)', height = 108 }: {
  bars: Slice[]; color?: string; height?: number;
}) {
  const max = Math.max(1, ...bars.map((b) => b.value));
  const desc = bars.map((b) => `${b.label} ${won(b.value)}`).join(', ');
  return (
    <div className="barc" style={{ height }} role="img" aria-label={desc}>
      {bars.map((b) => (
        <div className="barc-col" key={b.label} title={`${b.label} · ${won(b.value)}`}>
          <span className="barc-bar" style={{ height: `${(b.value / max) * 100}%`, background: color }} aria-hidden="true" />
          <span className="barc-lb" aria-hidden="true">{b.label}</span>
        </div>
      ))}
    </div>
  );
}
