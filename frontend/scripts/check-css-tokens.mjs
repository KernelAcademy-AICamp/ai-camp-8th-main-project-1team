/**
 * CSS 변수 무결성 검사 — 정의 없는 `var()`를 빌드 전에 잡는다.
 *
 * **왜 따로 봐야 하는가.** `var(--blue)`가 정의돼 있지 않으면 그 선언 하나만 조용히 무효가 된다.
 * 실제로 `:root` 블록을 통째로 날린 적이 있는데 `tsc`도 `vite build`도 통과했고,
 * **활성화된 버튼의 배경만 사라져** 흰 글자만 남았다 — 온보딩에서 "다음 버튼이 없어졌다"로
 * 나타나 가입이 통째로 막혔다. CSS는 틀려도 실패하지 않으므로 여기서 본다.
 *
 *   node scripts/check-css-tokens.mjs
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const DIR = 'src/styles';

const css = readdirSync(DIR)
  .filter((f) => f.endsWith('.css'))
  .map((f) => readFileSync(join(DIR, f), 'utf8'))
  .join('\n')
  // 주석은 뺀다 — 설명에 적어 둔 예시 변수명이 '사용'으로 잡히지 않게.
  .replace(/\/\*[\s\S]*?\*\//g, '');

const used = new Set([...css.matchAll(/var\((--[\w-]+)/g)].map((m) => m[1]));
const defined = new Set([...css.matchAll(/(--[\w-]+)\s*:/g)].map((m) => m[1]));
const missing = [...used].filter((v) => !defined.has(v)).sort();

if (missing.length > 0) {
  console.error(`\n✗ 정의 없는 CSS 변수 ${missing.length}개 — 그 선언은 무효가 된다:`);
  for (const v of missing) console.error(`    ${v}`);
  console.error('');
  process.exit(1);
}

console.log(`✓ CSS 변수 ${used.size}개 전부 정의됨`);
