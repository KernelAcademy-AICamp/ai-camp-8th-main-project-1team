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
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
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
