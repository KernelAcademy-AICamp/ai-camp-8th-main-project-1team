/**
 * 화면을 실제로 띄워 **그림으로 남긴다** — 프로토타입과 눈으로 대조하려는 것이다.
 *
 * 정적 검사는 "규칙이 있는가"를 보고 렌더 검사는 "값이 맞는가"를 보지만, <b>깨져 보이는가</b>는
 * 사람이 봐야 안다. 겹침·잘림·글자 깨짐·그림 누락은 숫자로 안 잡힌다.
 *
 *   node scripts/shoot.mjs [화면id …]        (없으면 ALL_SCREENS 전부)
 *   BASE=http://localhost:4180 OUT=shots
 */
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';

const BASE = (process.env.BASE ?? 'http://localhost:4180').replace(/\/$/, '');
const OUT = process.env.OUT ?? 'shots';
const WIDTH = Number(process.env.W ?? 390);
const HEIGHT = Number(process.env.H ?? 844);

const ALL = (() => {
  const src = readFileSync(new URL('../src/state/session.tsx', import.meta.url), 'utf8');
  const b = src.match(/ALL_SCREENS\s*=\s*\[([\s\S]*?)\]/);
  return [...b[1].matchAll(/'([a-z0-9-]+)'/g)].map((m) => m[1]);
})();

const routes = process.argv.slice(2).length ? process.argv.slice(2) : ALL;
mkdirSync(OUT, { recursive: true });

/* 연결 전에만 열리는 화면들 — 연동된 세션으로 열면 앱이 홈으로 되돌린다(App.tsx 강제 이동).
   그래서 이 화면들은 **연동 안 된 세션**으로 찍는다. */
const LINK_FLOW = new Set(['boot', 'walk', 'auth', 'connect']);

const browser = await chromium.launch();
const errors = [];

async function shoot(route, index, linked) {
  const ctx = await browser.newContext({
    viewport: { width: WIDTH, height: HEIGHT }, deviceScaleFactor: 2,
  });
  if (linked) {
    /* 실제 백엔드에 붙는다. 스텁으로 찍으면 "안 깨졌다"가 아니라 "빈 화면이 안 깨졌다"가 된다 —
       숫자가 길어져 칸을 넘치는 것, 목록이 길어 겹치는 것은 진짜 데이터라야 보인다.
       USER/TOKEN 은 `scripts/_probe.mjs` 와 같은 값을 환경에서 받는다. */
    const uid = process.env.USER_ID ?? '1';
    const token = process.env.AUTH_TOKEN ?? '';
    await ctx.addInitScript(([u, t]) => {
      localStorage.setItem('mydata_onboarded', 'true');
      localStorage.setItem('demo_user_id', u);
      if (t) localStorage.setItem('auth_token', t);
    }, [uid, token]);
  }
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`${route} :: ${e.message}`));
  await page.goto(`${BASE}/?s=${index}#/${route}`, { waitUntil: 'domcontentloaded' });
  /* 지킴이 홈은 동기화를 한 번 돌고 오므로 1.4초로는 모자랄 때가 있다 —
     스켈레톤을 찍어 놓고 "화면이 비었다"고 오판하지 않게 넉넉히 준다. */
  await page.waitForTimeout(Number(process.env.SETTLE ?? 3000));
  /* 이 앱의 스크롤 주체는 문서가 아니라 `.scroll` 컨테이너다. 그대로 찍으면 첫 화면만
     남아 <b>아래 절들이 없는 것처럼</b> 보인다 — 찍는 동안만 컨테이너를 펼쳐 전부 담는다. */
  await page.evaluate(() => {
    for (const el of document.querySelectorAll('.scroll')) {
      el.style.height = 'auto';
      el.style.maxHeight = 'none';
      el.style.overflow = 'visible';
    }
    document.querySelectorAll('.screen').forEach((el) => { el.style.height = 'auto'; });
  });
  await page.waitForTimeout(250);
  await page.screenshot({ path: `${OUT}/${route}.png`, fullPage: true });
  const at = await page.evaluate(() => location.hash.replace(/^#\/?/, ''));
  const overflow = await page.evaluate(() =>
    document.documentElement.scrollWidth - window.innerWidth);
  /* 글자가 칸을 넘쳤는가 — 잘림은 '깨져 보인다'의 가장 흔한 모양이다. */
  const clipped = await page.evaluate(() => [...document.querySelectorAll('*')]
    .filter((el) => {
      /* `.sr-only` 는 **일부러** 1×1 로 잘라 둔 것이다 — 보조기술에만 읽히는 글이라
         잘렸다고 세면 모든 화면이 빨개져 진짜 잘림이 묻힌다. */
      if (el.closest('.sr-only')) return false;
      /* 스스로 굴릴 수 있는 칸 안의 내용은 잘린 것이 아니다 — 손가락으로 내려 보면 다 있다. */
      if (el.querySelector(':scope > [style*="overflow"], :scope > .goal, :scope > .cards')) return false;
      const s = getComputedStyle(el);
      if (s.overflow === 'visible') return false;
      /* `text-overflow: ellipsis` 는 **일부러 줄인 것**이다 — 말줄임표가 보이면 잘림이 아니라
         디자인이다(가맹점명이 그렇게 줄어든다). */
      if (s.textOverflow === 'ellipsis') return false;
      if (el.scrollHeight <= el.clientHeight + 2 || s.overflowY !== 'hidden') return false;
      /* **글자가 잘렸을 때만** 센다. 그림을 일부러 잘라 넣은 카드(마이룸 미리보기처럼
         `overflow:hidden` 으로 넘치게 앉힌 것)까지 세면 의도한 연출이 위반으로 잡힌다. */
      return [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim())
        || [...el.querySelectorAll('*')].some((c) => {
          if (!c.textContent?.trim()) return false;
          const r = c.getBoundingClientRect(); const p = el.getBoundingClientRect();
          return r.bottom > p.bottom + 2 || r.top < p.top - 2;
        });
    })
    .slice(0, 3)
    .map((el) => `${el.tagName.toLowerCase()}.${String(el.className).slice(0, 24)}`));
  await ctx.close();
  const flags = [
    /* 스플래시는 2.45초 뒤 스스로 다음 장으로 간다(디자인) — 튕긴 것이 아니다. */
    at !== route && !(route === 'boot' && at === 'walk') ? `튕김→${at}` : '',
    overflow > 1 ? `가로넘침 ${overflow}px` : '',
    clipped.length ? `세로잘림 ${clipped.join(' ')}` : '',
  ].filter(Boolean).join('  ');
  console.log(`  ${route.padEnd(16)} ${flags || 'ok'}`);
}

for (const [i, r] of routes.entries()) await shoot(r, i, !LINK_FLOW.has(r));
await browser.close();
if (errors.length) {
  console.log('\n스크립트 오류:');
  for (const e of [...new Set(errors)]) console.log('   ' + e);
}
