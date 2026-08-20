/**
 * 여러 걸음을 밟아 가며 찍는다 — 한 화면 안에서 상태가 바뀌는 곳(온보딩·워크스루)용.
 *
 * 정지 화면 한 장으로는 "3단계 타일이 안 뜬다" 같은 것을 못 본다. 실제로 눌러 가며
 * 걸음마다 남긴다.
 *
 *   USER_ID=.. AUTH_TOKEN=.. node scripts/shoot-flow.mjs ob
 */
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';

const BASE = (process.env.BASE ?? 'http://localhost:4180').replace(/\/$/, '');
const OUT = process.env.OUT ?? 'shots';
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: Number(process.env.VW ?? 390), height: Number(process.env.VH ?? 844) }, deviceScaleFactor: 2 });
await ctx.addInitScript(([u, t]) => {
  localStorage.setItem('mydata_onboarded', 'true');
  localStorage.setItem('demo_user_id', u);
  if (t) localStorage.setItem('auth_token', t);
}, [process.env.USER_ID ?? '1', process.env.AUTH_TOKEN ?? '']);
const page = await ctx.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));

const shot = async (name) => {
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true });
  /* **정말 못 누르는가.** 눈에 보이는 조작 요소의 한가운데를 hit-test 해서, 그 자리를
     CTA 나 다른 것이 가로채면 사용자는 그것을 누를 수 없다. 숨은 단계 패널은 보이지
     않으므로 세지 않는다 — 그것까지 세면 오탐이 진짜를 덮는다. */
  const stuck = await page.evaluate(() => {
    const out = [];
    for (const el of document.querySelectorAll('.screen .scroll, .screen [class*=box]')) {
      if (el.scrollHeight > el.clientHeight + 2 && getComputedStyle(el).overflowY === 'hidden'
          && el.checkVisibility({ opacityProperty: true, visibilityProperty: true }))
        out.push(`잘림(스크롤 불가): .${el.className.toString().slice(0,22)} ${el.scrollHeight}>${el.clientHeight}`);
    }
    for (const el of document.querySelectorAll('.screen button, .screen a, .screen input, .screen [role=button]')) {
      if (!el.checkVisibility({ opacityProperty: true, visibilityProperty: true })) continue;
      const b = el.getBoundingClientRect();
      if (b.width < 4 || b.height < 4) continue;
      const cx = b.left + b.width / 2, cy = b.top + b.height / 2;
      if (cx < 0 || cy < 0 || cx > innerWidth || cy > innerHeight) continue;
      const hit = document.elementFromPoint(cx, cy);
      if (!hit || hit === el || el.contains(hit) || hit.contains(el)) continue;
      /* 스크롤해서 걷어낼 수 있으면 갇힌 것이 아니다 — 조상 중에 실제로 구르는 것이
         있으면 사용자는 그 요소를 CTA 밖으로 올려 누를 수 있다. */
      let scrollable = false;
      for (let a = el.parentElement; a; a = a.parentElement) {
        const oy = getComputedStyle(a).overflowY;
        if ((oy === 'auto' || oy === 'scroll') && a.scrollHeight > a.clientHeight + 2) { scrollable = true; break; }
      }
      if (!scrollable)
        out.push(`못 누름: "${(el.textContent||'').trim().slice(0,12) || '(이름없음)'}" ← .${(hit.className||'').toString().slice(0,20)} 가 가림`);
    }
    return out;
  });
  for (const s of stuck) console.log(`      ⚠ ${s}`);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  console.log(`  ${name.padEnd(18)} ${overflow > 1 ? `가로넘침 ${overflow}px` : 'ok'}`);
};

const which = process.argv[2] ?? 'ob';

if (which === 'ob') {
  await page.goto(`${BASE}/#/ob`, { waitUntil: 'domcontentloaded' });
  // 1단계는 브리핑이 끝나면 스스로 2로 넘어간다. 넘어가기 전에 한 장.
  await page.waitForTimeout(3200); await shot('ob-1분석');
  await page.waitForTimeout(4200); await shot('ob-2절감');
  await page.getByRole('button', { name: '다음' }).click();
  await page.waitForTimeout(1800); await shot('ob-3성역');
  await page.getByRole('button', { name: '다음' }).click();
  await page.waitForTimeout(1800); await shot('ob-4줄일항목');
  await page.getByRole('button', { name: /챌린지 만들기/ }).click();
  await page.waitForTimeout(1800); await shot('ob-5목표');
}

if (which === 'walk') {
  await ctx.clearCookies();
  await page.goto(`${BASE}/#/walk`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200); await shot('walk-1');
  await page.getByRole('button', { name: '다음' }).click();
  await page.waitForTimeout(1400); await shot('walk-2');
  await page.getByRole('button', { name: '다음' }).click();
  await page.waitForTimeout(1400); await shot('walk-3');
}

await browser.close();
if (errors.length) { console.log('\n스크립트 오류:'); for (const e of [...new Set(errors)]) console.log('   ' + e); }
