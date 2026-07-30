/**
 * 버튼·칩의 **글자 대비 4.5:1** 검사 (KWCAG 2.2 검사항목 5.4.3).
 *
 * **왜 필요한가.** 개편안의 브랜드 그린(#00B14F)에 흰 글자는 2.84:1로 미달인데, 눈으로는
 * 그럭저럭 읽혀서 놓친다. 더 나쁜 것은 비활성 버튼이었다 — 연한 초록에 흰 글자라 배경에 묻혀
 * **버튼이 사라진 것처럼** 보였고, 온보딩에서 "다음이 없어졌다"로 읽혀 가입이 막혔다.
 * 색은 틀려도 빌드가 실패하지 않으므로 숫자로 봐야 한다.
 *
 * 테두리가 있는 버튼(.chip·.btn-ghost 등)은 면이 배경과 비슷해도 경계가 보이므로,
 * 면 대비는 따지지 않고 **글자 대비만** 본다.
 *
 *   node scripts/check-contrast.mjs
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const DIR = 'src/styles';
const MIN = 4.5;

const css = readdirSync(DIR)
  .filter((f) => f.endsWith('.css'))
  .map((f) => readFileSync(join(DIR, f), 'utf8'))
  .join('\n')
  .replace(/\/\*[\s\S]*?\*\//g, '');

const tokens = Object.fromEntries(
  [...css.matchAll(/(--[\w-]+)\s*:\s*(#[0-9A-Fa-f]{3,6})/g)].map((m) => [m[1], m[2]]),
);

const toRgb = (raw) => {
  let v = raw.trim();
  const varMatch = v.match(/^var\((--[\w-]+)\)/);
  if (varMatch) v = tokens[varMatch[1]] ?? '';
  const six = v.match(/^#([0-9A-Fa-f]{6})$/);
  if (six) return [0, 2, 4].map((i) => parseInt(six[1].slice(i, i + 2), 16));
  const three = v.match(/^#([0-9A-Fa-f]{3})$/);
  if (three) return [...three[1]].map((c) => parseInt(c + c, 16));
  return null;                                   // rgba·gradient·transparent 등은 건너뛴다
};

const luminance = ([r, g, b]) => {
  const f = [r, g, b].map((x) => {
    const s = x / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * f[0] + 0.7152 * f[1] + 0.0722 * f[2];
};
const contrast = (a, b) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};

// 사용자가 누르는 것들 — 여기서 글자가 안 읽히면 조작을 못 한다.
const TARGET = /\.(btn|buy-btn|chip|fchips button|seg button|entry|act|aux-btn|skipbtn)[\w.:>\- ]*$/;

const fails = [];
let checked = 0;
for (const m of css.matchAll(/([^{}]+)\{([^}]*)\}/g)) {
  const sel = m[1].trim();
  if (!TARGET.test(sel)) continue;
  const body = m[2];
  const bg = body.match(/(?:^|;)\s*background(?:-color)?\s*:\s*([^;]+)/);
  const fg = body.match(/(?:^|;)\s*color\s*:\s*([^;]+)/);
  if (!bg || !fg) continue;
  const b = toRgb(bg[1]);
  const f = toRgb(fg[1]);
  if (!b || !f) continue;
  checked++;
  const r = contrast(b, f);
  if (r < MIN) fails.push([sel, r]);
}

if (fails.length > 0) {
  console.error(`\n✗ 글자 대비 ${MIN}:1 미달 ${fails.length}건 (KWCAG 5.4.3):`);
  for (const [sel, r] of fails) console.error(`    ${sel}  ${r.toFixed(2)}:1`);
  console.error('');
  process.exit(1);
}

console.log(`✓ 조작 요소 ${checked}종 글자 대비 ${MIN}:1 이상`);
