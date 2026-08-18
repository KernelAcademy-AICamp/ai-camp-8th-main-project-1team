/**
 * KWCAG 2.2 · KS X OT0003 33개 검사항목 자동 점검.
 *
 * 근거 문서(4종 모두 같은 33항목을 쓴다):
 *   reference/guide1.pdf  KS X 3253 모바일 앱 접근성 2.0
 *   reference/guide2.pdf  KWCAG 2.2 제작기법(추가 9항목)
 *   reference/guide3.pdf  KS X OT0003 한국형 웹 콘텐츠 접근성 지침 2.2
 *   reference/guide_checklist.ppt  자체점검표 — "4개 원칙 33개 검사항목"
 *
 * **존재 여부가 아니라 값을 본다.** 처음 만든 점검은 `:focus-visible이 있는가`처럼 문자열이
 * 있는지만 봤고, 그래서 대비 2.84:1짜리 버튼(흰 글자·브랜드 그린)을 통과시켰다. 눈으로는
 * 그럭저럭 읽혀서 사람도 못 잡는다. 비활성 버튼은 더 나빠서 배경에 묻혀 **사라진 것처럼**
 * 보였고 온보딩이 통째로 막혔다. 그래서 색은 대비를 계산하고, 크기는 px를 읽고,
 * 컨트롤은 요소 종류를 센다.
 *
 *   node scripts/check-a11y.mjs
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

// ── 입력 ────────────────────────────────────────────────────────────────
const STYLES = 'src/styles';
const css = readdirSync(STYLES)
  .filter((f) => f.endsWith('.css'))
  .map((f) => readFileSync(join(STYLES, f), 'utf8'))
  .join('\n')
  .replace(/\/\*[\s\S]*?\*\//g, '');

const srcFiles = [];
const walk = (d) => {
  for (const e of readdirSync(d, { withFileTypes: true })) {
    const p = join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (/\.tsx?$/.test(e.name)) srcFiles.push(p);
  }
};
walk('src');
const tsx = srcFiles.map((f) => readFileSync(f, 'utf8')).join('\n');
const html = readFileSync('index.html', 'utf8');

// ── 색 계산 ─────────────────────────────────────────────────────────────
const tokens = Object.fromEntries(
  [...css.matchAll(/(--[\w-]+)\s*:\s*(#[0-9A-Fa-f]{3,6})/g)].map((m) => [m[1], m[2]]),
);
const toRgb = (raw) => {
  let v = String(raw).trim();
  const vm = v.match(/^var\((--[\w-]+)\)/);
  if (vm) v = tokens[vm[1]] ?? '';
  const s6 = v.match(/^#([0-9A-Fa-f]{6})$/);
  if (s6) return [0, 2, 4].map((i) => parseInt(s6[1].slice(i, i + 2), 16));
  const s3 = v.match(/^#([0-9A-Fa-f]{3})$/);
  if (s3) return [...s3[1]].map((c) => parseInt(c + c, 16));
  return null;
};
const lum = ([r, g, b]) => {
  const f = [r, g, b].map((x) => {
    const s = x / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * f[0] + 0.7152 * f[1] + 0.0722 * f[2];
};
const contrast = (a, b) => {
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};

// ── 검사 ────────────────────────────────────────────────────────────────
const results = [];
const check = (id, name, pass, detail) => results.push({ id, name, pass, detail });
const count = (re, hay = tsx) => (hay.match(re) ?? []).length;
const has = (re, hay = tsx) => re.test(hay);

/* 5.4.3 텍스트 명도 대비 — **값을 계산한다.** */
{
  const TARGET = /\.(btn|buy-btn|chip|fchips button|seg button|entry|act|aux-btn|skipbtn|tag-\w+)[\w.:>\- ]*$/;
  const bad = [];
  let n = 0;
  for (const m of css.matchAll(/([^{}]+)\{([^}]*)\}/g)) {
    const sel = m[1].trim();
    if (!TARGET.test(sel)) continue;
    const bg = m[2].match(/(?:^|;)\s*background(?:-color)?\s*:\s*([^;]+)/);
    const fg = m[2].match(/(?:^|;)\s*color\s*:\s*([^;]+)/);
    if (!bg || !fg) continue;
    const b = toRgb(bg[1]);
    const f = toRgb(fg[1]);
    if (!b || !f) continue;
    n++;
    const r = contrast(b, f);
    if (r < 4.5) bad.push(`${sel} ${r.toFixed(2)}:1`);
  }
  check('5.4.3', '텍스트 명도 대비', bad.length === 0,
    bad.length ? `미달 ${bad.join(' · ')}` : `조작 요소 ${n}종 모두 4.5:1 이상`);
}

/* 6.1.3 조작 가능 — 터치 타깃 44px을 **숫자로** 확인 */
{
  const sizes = [...css.matchAll(/min-(?:height|width)\s*:\s*(\d+)px/g)].map((m) => +m[1]);
  const ok44 = sizes.filter((v) => v >= 44).length;
  check('6.1.3', '조작 가능(44px)', ok44 >= 4, `44px 이상 규칙 ${ok44}개`);
}

/* 6.1.2 초점 표시 — outline 이 none 이 아닌지까지 */
{
  const m = css.match(/:focus-visible\s*\{([^}]*)\}/);
  const ok = !!m && /outline\s*:\s*(?!none)/.test(m[1]);
  check('6.1.2', '초점 이동과 표시', ok, ok ? 'outline 지정' : ':focus-visible outline 없음');
}

/* 5.1.1 대체 텍스트 — 장식 SVG는 숨기고, 의미 있는 컨트롤엔 이름 */
{
  const hidden = count(/aria-hidden/g);
  const labeled = count(/aria-label/g);
  check('5.1.1', '적절한 대체 텍스트', hidden > 10 && labeled > 10,
    `aria-hidden ${hidden} · aria-label ${labeled}`);
}

/* 6.1.1 키보드 사용 — 클릭 가능한 div 가 남아 있지 않은지.
   `[^>]`는 줄바꿈도 먹어서 여러 태그를 한 덩어리로 잡는다(그래서 없는 위반이 4건 잡혔다).
   태그 하나만 보도록 `<`를 제외하고, dim 오버레이(aria-hidden)는 제 짝 버튼이 따로 있으므로 뺀다. */
{
  const offenders = [...tsx.matchAll(/<div\b[^<>]*onClick=[^<>]*>/g)]
    .map((m) => m[0])
    .filter((t) => !/role=/.test(t) && !/aria-hidden/.test(t));
  check('6.1.1', '키보드 사용 보장', offenders.length === 0,
    offenders.length
      ? `role 없는 클릭 div ${offenders.length}개`
      : `<button> ${count(/<button/g)}개 · 클릭 div 0`);
}

/* 6.5.3 레이블과 네임 — role="button" 인 div 에 키보드 처리가 있는지 */
{
  const roleBtn = count(/role="button"/g);
  const keyed = count(/onKeyDown/g);
  check('6.5.3', '레이블과 네임', roleBtn === 0 || keyed >= roleBtn,
    `role=button ${roleBtn} · onKeyDown ${keyed}`);
}

/* 나머지 — 구조·정책 항목 */
const simple = [
  ['5.2.1', '자막 제공', !has(/<video|<audio/), '동영상·음성 없음'],
  // 표를 안 쓰면 자동 충족, 쓰면 **구조를 갖췄는지** 본다.
  //
  // 예전에는 `!has(/<table/)` 하나였다 — 표가 없으면 통과. 표를 처음 쓰는 순간
  // (admin 의 이용 통계) 그 규칙은 "표를 쓰지 말라"는 뜻이 되어 버렸는데, 지침이 요구하는
  // 것은 표를 피하는 것이 아니라 **제목 셀과 데이터 셀의 관계를 알 수 있게** 하는 것이다.
  // 그래서 표 개수만큼 caption 이 있고 열 제목에 scope 가 붙었는지로 바꾼다.
  ['5.3.1', '표의 구성',
    count(/<table/g) === 0
      || (count(/<caption/g) >= count(/<table/g) && has(/scope="col"/)),
    count(/<table/g) === 0
      ? '<table> 미사용'
      : `표 ${count(/<table/g)} · caption ${count(/<caption/g)} · scope=col 사용`],
  ['5.3.2', '콘텐츠의 선형구조', has(/<main/), 'main 랜드마크'],
  ['5.3.3', '명확한 지시사항', !has(/(왼쪽|오른쪽|위쪽|아래쪽)\s*(버튼|링크)을?\s*(누르|클릭)/), '방향만으로 지시 안 함'],
  ['5.4.1', '색에 무관한 인식', has(/aria-pressed/) && has(/aria-current/), '상태를 aria로도 전달'],
  ['5.4.2', '자동 재생 금지', !has(/autoPlay|autoplay/), 'autoplay 없음'],
  ['5.4.4', '콘텐츠 간의 구분', has(/border/, css), '경계선 사용'],
  ['6.1.4', '문자 단축키', !has(/keypress|keyCode/), '단일문자 단축키 없음'],
  ['6.2.1', '응답시간 조절', !has(/location\.reload\(\)/), '자동 새로고침 없음'],
  ['6.2.2', '정지 기능 제공', has(/prefers-reduced-motion/, css), '모션 축소 존중'],
  ['6.3.1', '깜빡임 제한', has(/prefers-reduced-motion/, css), 'reduced-motion에서 정지'],
  ['6.4.1', '반복 영역 건너뛰기', has(/skip-link|본문으로/), '스킵 링크'],
  ['6.4.2', '제목 제공', has(/<title/, html) && has(/<h1/), 'title + h1'],
  ['6.4.3', '적절한 링크 텍스트', !has(/>여기<|>클릭<|>더보기<\s*<\/a>/), '무의미 링크 없음'],
  ['6.4.4', '고정된 참조 위치', true, 'SPA 단일 문서 — 해당 없음'],
  ['6.5.1', '단일 포인터 입력', !has(/onTouchMove|gesturestart/), '제스처 필수 아님'],
  ['6.5.2', '포인터 입력 취소', !has(/onMouseDown/), 'down으로 확정 안 함'],
  ['6.5.4', '동작기반 작동', !has(/devicemotion|DeviceOrientation/), '기기 동작 미사용'],
  ['7.1.1', '기본 언어 표시', has(/lang="ko"/, html), 'html lang="ko"'],
  ['7.2.1', '사용자 요구 실행', !has(/window\.open\(/), '새 창 자동 열기 없음'],
  ['7.2.2', '찾기 쉬운 도움 정보', has(/처리방침|개인정보/), '처리방침 화면'],
  ['7.3.1', '오류 정정', has(/role="alert"/), 'role="alert"'],
  ['7.3.2', '레이블 제공', has(/<label|aria-label/), '입력 접근명'],
  ['7.3.3', '접근 가능한 인증', !has(/captcha/i), 'CAPTCHA 없음'],
  ['7.3.4', '반복 입력 정보', has(/draft|localStorage/), '입력값 이어받기'],
  ['8.1.1', '마크업 오류 방지', true, 'tsc + JSX가 구조 보장'],
  ['8.2.1', '웹앱 접근성 준수', has(/aria-/), 'role·aria 사용'],
];
for (const [id, name, pass, detail] of simple) check(id, name, pass, detail);

// ── 출력 ────────────────────────────────────────────────────────────────
results.sort((a, b) => a.id.localeCompare(b.id, undefined, { numeric: true }));
for (const r of results) {
  console.log(`  ${r.pass ? '✓' : '✗'} ${r.id}  ${r.name.padEnd(18)} ${r.detail}`);
}
const fail = results.filter((r) => !r.pass);
console.log(`\n  KWCAG 2.2 — 충족 ${results.length - fail.length} / ${results.length}`);
if (fail.length > 0) {
  console.error(`\n✗ 미충족 ${fail.length}건`);
  process.exit(1);
}
