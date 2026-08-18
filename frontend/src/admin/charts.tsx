/**
 * 통계 화면이 쓰는 그림 조각들 — 라이브러리 없이 SVG 로 직접 그린다.
 *
 * 라이브러리를 안 들이는 이유: 여기서 필요한 것은 막대·선·도넛 넷뿐이고, 차트 라이브러리는
 * 그 넷을 위해 수백 KB 와 자기 나름의 테마 체계를 함께 들고 온다. admin 번들은 사용자에게
 * 전달되지 않지만, 그렇다고 무거워도 되는 것은 아니다.
 *
 * ## 공통 규칙
 *
 * - **숫자를 색으로만 구분하지 않는다.** 조각마다 이름표가 함께 붙는다 (KWCAG 5.4.1).
 * - `<title>`을 넣어 마우스를 올리면 정확한 값이 뜬다. 눈금으로 읽게 하지 않는다.
 * - `role="img"` + `aria-label` — 그림을 못 보는 사람에게도 무엇을 그린 것인지 말한다.
 * - 값이 0이면 흐리게 그린다. 아예 안 그리면 '그날이 없었던 것'과 구분이 안 된다.
 */

/** 자릿수를 맞춰 읽기 좋게. */
const fmt = (v: number) => v.toLocaleString('ko-KR');

/** 색은 다섯 개까지만 돌려 쓴다. 더 늘리면 서로 구분이 안 된다. */
const HUES = ['#3182F6', '#00A661', '#F5A623', '#8B5CF6', '#EC5E7B', '#8B95A1'];

// ── 여러 계열을 겹쳐 보는 큰 그래프 ────────────────────────────────────────

export interface SeriesPoint { label: string; bars: number; line: number; extra?: number }

/**
 * 전체 흐름을 한눈에 — **막대(세션) + 선(사용자)** 을 같은 시간축에 겹친다.
 *
 * 두 계열을 나란히 두는 이유: 세션만 보면 "많이 들어왔다"는 알아도 그게 여러 사람인지
 * 한 사람이 여러 번인지 모른다. 선이 아래에 붙어 있으면 그 차이가 바로 보인다.
 *
 * 축을 **두 개** 쓴다(왼쪽 막대, 오른쪽 선). 사람 수는 늘 세션 수보다 작아서 같은 축에
 * 두면 선이 바닥에 눌려 아무것도 안 보인다.
 */
export function OverviewChart({ points, barName, lineName, height = 260 }: {
  points: SeriesPoint[]; barName: string; lineName: string; height?: number;
}) {
  const W = 1000;
  const H = 320;
  const PAD = { top: 24, right: 52, bottom: 44, left: 52 };
  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;

  const barMax = Math.max(1, ...points.map((p) => p.bars));
  const lineMax = Math.max(1, ...points.map((p) => p.line));
  const step = innerW / Math.max(1, points.length);
  const x = (i: number) => PAD.left + step * (i + 0.5);
  const yLine = (v: number) => PAD.top + innerH - (v / lineMax) * innerH;

  // 가로 눈금 넷. 더 그으면 격자가 데이터보다 눈에 띈다.
  const ticks = [0, 0.25, 0.5, 0.75, 1];
  const linePath = points
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${yLine(p.line).toFixed(1)}`)
    .join(' ');
  // 날짜가 많으면 이름표를 솎아 낸다 — 겹쳐 뭉개진 글자는 없느니만 못하다.
  const labelEvery = Math.ceil(points.length / 12);

  return (
    <svg className="chart" viewBox={`0 0 ${W} ${H}`} style={{ height }} role="img"
         aria-label={`날짜별 ${barName}과 ${lineName}`}>
      {ticks.map((t) => (
        <g key={t}>
          <line className="grid" x1={PAD.left} x2={W - PAD.right}
                y1={PAD.top + innerH * (1 - t)} y2={PAD.top + innerH * (1 - t)} />
          <text className="tick" x={PAD.left - 8} y={PAD.top + innerH * (1 - t) + 4}
                textAnchor="end">{fmt(Math.round(barMax * t))}</text>
          <text className="tick alt" x={W - PAD.right + 8} y={PAD.top + innerH * (1 - t) + 4}>
            {fmt(Math.round(lineMax * t))}
          </text>
        </g>
      ))}

      {points.map((p, i) => {
        const h = Math.max((p.bars / barMax) * innerH, p.bars > 0 ? 2 : 0);
        return (
          <rect key={`b${i}`} className={p.bars ? 'bar' : 'bar zero'}
                x={x(i) - step * 0.3} width={step * 0.6}
                y={PAD.top + innerH - h} height={h} rx={3}>
            <title>{`${p.label} · ${barName} ${fmt(p.bars)} · ${lineName} ${fmt(p.line)}`}</title>
          </rect>
        );
      })}

      <path className="line" d={linePath} />
      {points.map((p, i) => (
        <circle key={`d${i}`} className="dot" cx={x(i)} cy={yLine(p.line)} r={3.5}>
          <title>{`${p.label} · ${lineName} ${fmt(p.line)}`}</title>
        </circle>
      ))}

      <line className="axis" x1={PAD.left} x2={W - PAD.right}
            y1={PAD.top + innerH} y2={PAD.top + innerH} />
      {points.map((p, i) => (i % labelEvery === 0 ? (
        <text key={`t${i}`} className="tick" x={x(i)} y={H - 22} textAnchor="middle">
          {p.label.slice(5)}
        </text>
      ) : null))}

      <g className="legend" transform={`translate(${PAD.left}, ${H - 6})`}>
        <rect className="bar" x={0} y={-9} width={11} height={11} rx={2} />
        <text className="tick" x={17} y={0}>{barName}</text>
        <line className="line" x1={70} x2={92} y1={-4} y2={-4} />
        <circle className="dot" cx={81} cy={-4} r={3.5} />
        <text className="tick" x={98} y={0}>{lineName}</text>
      </g>
    </svg>
  );
}

// ── 구성비 ─────────────────────────────────────────────────────────────────

export interface Slice { label: string; value: number }

/**
 * 도넛 — **구성비**에만 쓴다. 순위 비교에는 막대가 낫다.
 *
 * 가운데에 합계를 적는다. 조각 비율만 보여 주면 "그래서 몇 개인데"에 답을 못 한다.
 */
export function Donut({ slices, total, unit, size = 168 }: {
  slices: Slice[]; total: number; unit: string; size?: number;
}) {
  const R = 70;
  const STROKE = 26;
  const C = 2 * Math.PI * R;
  const sum = slices.reduce((a, s) => a + s.value, 0) || 1;
  let offset = 0;

  return (
    <div className="donut-wrap">
      <svg viewBox="0 0 180 180" style={{ width: size, height: size }} role="img"
           aria-label={`구성비 — ${slices.map((s) => `${s.label} ${s.value}`).join(', ')}`}>
        <g transform="translate(90,90) rotate(-90)">
          <circle className="track" r={R} fill="none" strokeWidth={STROKE} />
          {slices.map((s, i) => {
            const len = (s.value / sum) * C;
            const dash = `${len} ${C - len}`;
            const el = (
              <circle key={s.label} r={R} fill="none" strokeWidth={STROKE}
                      stroke={HUES[i % HUES.length]} strokeDasharray={dash}
                      strokeDashoffset={-offset}>
                <title>{`${s.label} · ${fmt(s.value)}${unit} (${Math.round((s.value / sum) * 100)}%)`}</title>
              </circle>
            );
            offset += len;
            return el;
          })}
        </g>
        <text className="donut-total" x="90" y="86" textAnchor="middle">{fmt(total)}</text>
        <text className="donut-unit" x="90" y="104" textAnchor="middle">{unit}</text>
      </svg>
      <ul className="legend-list">
        {slices.map((s, i) => (
          <li key={s.label}>
            <i style={{ background: HUES[i % HUES.length] }} />
            <span className="k">{s.label}</span>
            <span className="v">{fmt(s.value)}{unit}</span>
            <span className="p">{Math.round((s.value / sum) * 100)}%</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

// ── 세로 막대 ──────────────────────────────────────────────────────────────

/**
 * 순서가 뜻을 갖는 축(시간대·요일·구간)에 쓴다.
 *
 * 선이 아니라 막대인 이유: 시간대 사이에는 연속성이 없다. 선을 그으면 "9시와 10시 사이"라는
 * 있지도 않은 값을 그린 것이 된다.
 */
export function Bars({ items, unit, height = 180, highlight }: {
  items: Slice[]; unit: string; height?: number; highlight?: (s: Slice) => boolean;
}) {
  const W = 640;
  const H = 200;
  const PAD = { top: 16, right: 8, bottom: 30, left: 8 };
  const innerH = H - PAD.top - PAD.bottom;
  const max = Math.max(1, ...items.map((s) => s.value));
  const step = (W - PAD.left - PAD.right) / Math.max(1, items.length);

  return (
    <svg className="chart" viewBox={`0 0 ${W} ${H}`} style={{ height }} role="img"
         aria-label={items.map((s) => `${s.label} ${s.value}`).join(', ')}>
      {items.map((s, i) => {
        const h = Math.max((s.value / max) * innerH, s.value > 0 ? 2 : 0);
        const x = PAD.left + step * i;
        return (
          <g key={s.label}>
            <rect className={s.value === 0 ? 'bar zero' : highlight?.(s) ? 'bar hot' : 'bar'}
                  x={x + step * 0.18} width={step * 0.64}
                  y={PAD.top + innerH - h} height={h} rx={3}>
              <title>{`${s.label} · ${fmt(s.value)}${unit}`}</title>
            </rect>
            <text className="tick" x={x + step / 2} y={H - 10} textAnchor="middle">{s.label}</text>
          </g>
        );
      })}
      <line className="axis" x1={PAD.left} x2={W - PAD.right}
            y1={PAD.top + innerH} y2={PAD.top + innerH} />
    </svg>
  );
}

// ── 리텐션 곡선 ────────────────────────────────────────────────────────────

/**
 * 재방문율 — 경과일이 축이라 **간격이 고르지 않다**(1·3·7·14·30).
 *
 * 그래서 눈금을 등간격으로 두지 않고 실제 날짜 비율대로 놓는다. 등간격으로 그리면
 * D14→D30 의 뚝 떨어지는 구간이 실제보다 급해 보인다.
 */
export function RetentionCurve({ points, height = 200 }: {
  points: { day: number; rate: number | null; cohort: number }[]; height?: number;
}) {
  const usable = points.filter((p) => p.rate !== null);
  const W = 640;
  const H = 220;
  const PAD = { top: 18, right: 20, bottom: 34, left: 40 };
  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;
  const maxDay = Math.max(1, ...points.map((p) => p.day));
  const x = (d: number) => PAD.left + (d / maxDay) * innerW;
  const y = (r: number) => PAD.top + innerH - (r / 100) * innerH;

  if (usable.length === 0) return <p className="empty">아직 판단할 만큼 시간이 지나지 않았습니다.</p>;

  const path = usable
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.day).toFixed(1)},${y(p.rate!).toFixed(1)}`)
    .join(' ');
  const area = `${path} L${x(usable[usable.length - 1].day).toFixed(1)},${PAD.top + innerH}`
    + ` L${x(usable[0].day).toFixed(1)},${PAD.top + innerH} Z`;

  return (
    <svg className="chart" viewBox={`0 0 ${W} ${H}`} style={{ height }} role="img"
         aria-label={usable.map((p) => `D${p.day} ${p.rate}%`).join(', ')}>
      {[0, 25, 50, 75, 100].map((t) => (
        <g key={t}>
          <line className="grid" x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} />
          <text className="tick" x={PAD.left - 8} y={y(t) + 4} textAnchor="end">{t}%</text>
        </g>
      ))}
      <path className="area" d={area} />
      <path className="line" d={path} />
      {usable.map((p) => (
        <g key={p.day}>
          <circle className="dot" cx={x(p.day)} cy={y(p.rate!)} r={4}>
            <title>{`D${p.day} · ${p.rate}% (대상 ${p.cohort}명)`}</title>
          </circle>
          <text className="tick" x={x(p.day)} y={H - 12} textAnchor="middle">D{p.day}</text>
        </g>
      ))}
    </svg>
  );
}

// ── 가로 막대 (순위) ───────────────────────────────────────────────────────

/**
 * 순위 목록 — 막대 길이가 곧 비교다.
 *
 * 이름이 긴 항목(화면 id·주소)이 많아 가로로 눕힌다. 세로 막대에 긴 이름을 붙이면
 * 글자가 겹치거나 기울어져 읽기 어려워진다.
 */
export function RankBars({ rows, unit = '', aside, max: given, onExplain }: {
  rows: { key: string; label: string; note?: string; value: number; aside?: number }[];
  unit?: string; aside?: string; max?: number;
  onExplain?: (key: string) => void;
}) {
  const max = given ?? Math.max(1, ...rows.map((r) => r.value));
  return (
    <ul className="rank">
      {rows.map((r, i) => (
        <li key={`${r.key}-${i}`}>
          <span className="fill" style={{ width: `${(r.value / max) * 100}%` }} />
          <span className="row">
            <span className="name">
              {r.label}
              {r.note && <small>{r.note}</small>}
            </span>
            {onExplain && (
              <button type="button" className="why" title={`${r.label} 설명 보기`}
                      onClick={() => onExplain(r.key)}>?</button>
            )}
            <span className="val">{fmt(r.value)}{unit}</span>
            {r.aside !== undefined && <span className="aside">{fmt(r.aside)}{aside}</span>}
          </span>
        </li>
      ))}
    </ul>
  );
}
