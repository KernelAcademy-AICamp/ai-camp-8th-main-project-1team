/**
 * <b>올릴 `.ait` 가 방금 시험한 `dist` 그대로인지 확인한다.</b>
 *
 * `ait build` 는 `webBundleDir`(=`dist`)에 있는 것을 압축할 뿐 다시 빌드하지 않는다. 그래서
 * `vite build` 를 빼먹으면 낡은 화면이 그대로 검수를 통과해 운영에 나간다 — 2026-08-30 에
 * 실제로 그렇게 나갔다. 올리기 직전에 반드시 이 검사를 통과시킨다.
 */
import { execSync } from 'child_process';
import { readFileSync, readdirSync, statSync, mkdtempSync } from 'fs';
import { createHash } from 'crypto';
import { tmpdir } from 'os';
import { join } from 'path';

const sha = (p) => createHash('sha256').update(readFileSync(p)).digest('hex');
const listing = (dir) => readdirSync(dir).sort().map((f) => `${f} ${sha(join(dir, f))}`);

const tmp = mkdtempSync(join(tmpdir(), 'ait-'));
// `.ait` 는 앞에 머리말이 붙어 있어 unzip 이 경고와 함께 1 로 끝난다 — 경고는 넘긴다.
execSync(`unzip -q -o moaa.ait -d ${tmp} || true`, { stdio: 'pipe', shell: '/bin/sh' });

const a = listing('dist/assets');
const b = listing(join(tmp, 'sources/assets'));
let bad = 0;
const say = (ok, s) => { if (!ok) bad++; console.log(`  ${ok ? '✔' : '✗'} ${s}`); };

say(a.length === b.length && a.every((x, i) => x === b[i]),
    `.ait 안이 dist/assets 와 같다 (${a.length}개)`);

// 원본이 dist 보다 새로우면 빌드를 빼먹은 것이다
function newest(dir) {
  let m = 0;
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    m = Math.max(m, e.isDirectory() ? newest(p) : statSync(p).mtimeMs);
  }
  return m;
}
const src = newest('src'), built = newest('dist'), ait = statSync('moaa.ait').mtimeMs;
say(built > src, `dist 가 src 보다 새롭다 (${new Date(src).toLocaleTimeString('ko-KR')} → ${new Date(built).toLocaleTimeString('ko-KR')})`);
say(ait > built, `moaa.ait 가 dist 보다 새롭다 (${new Date(ait).toLocaleTimeString('ko-KR')})`);

// 고친 내용이 실제로 들어갔는지 — 눈으로 확인한 자리를 못으로 박는다
const css = readFileSync(join(tmp, `sources/assets/${readdirSync(join(tmp, 'sources/assets')).find((f) => f.endsWith('.css'))}`), 'utf8');
const js = readFileSync(join(tmp, `sources/assets/${readdirSync(join(tmp, 'sources/assets')).find((f) => f.endsWith('.js'))}`), 'utf8');
say(/\.sw-del\{[^}]*opacity:0[^}]*\}/.test(css), '빨간 바탕이 평소에 숨어 있다 (.sw-del opacity:0)');
say(css.includes('display:flow-root'), '몸통이 자식 여백을 가둔다 (display:flow-root)');
say(/\.sw\{[^}]*touch-action:pan-y/.test(css), '세로 훑기를 브라우저에 넘긴다 (touch-action:pan-y)');
say(/abs\(\w+\)\*1\.5/.test(js), '가로가 1.5배 뚜렷할 때만 붙잡는다');

console.log(bad === 0 ? '\n올려도 되는 번들\n' : `\n올리면 안 된다 — ${bad}건\n`);
process.exit(bad ? 1 : 0);
