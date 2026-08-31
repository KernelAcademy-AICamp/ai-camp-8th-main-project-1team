/** 진짜 터치로 시험한다 — 지우기, 훑기, 짧게 밀기, 세로 스크롤. */
import { chromium, devices } from 'playwright';

const browser = await chromium.launch();
const ctx = await browser.newContext({ ...devices['Galaxy S9+'], hasTouch: true, isMobile: true });
const page = await ctx.newPage();
const errs = [];
page.on('pageerror', (e) => errs.push(String(e)));
const cdp = await ctx.newCDPSession(page);
const touch = (t, x, y) => cdp.send('Input.dispatchTouchEvent', { type: t, touchPoints: t === 'touchEnd' ? [] : [{ x, y }] });
const rows = () => page.evaluate(() => JSON.parse(localStorage.getItem('moa.rows.v1') || '[]').length);

async function fresh() {
  // 해시만 같은 주소로 goto 하면 브라우저가 문서 안 이동으로 처리해 새로 고쳐지지 않는다.
  await page.goto('http://localhost:8899/demo.html#/transactions');
  await page.reload({ waitUntil: 'load' });
  await page.waitForTimeout(2200);
  const top = await page.evaluate(() => document.querySelector('.scroll')?.scrollTop ?? -1);
  if (top > 0) console.log(`  (경고: 스크롤이 ${top} 에서 시작)`);
}
async function firstRow() {
  const r = page.locator('.txn-item.sw').first();
  const b = await r.boundingBox();
  return { name: (await r.locator('.nm').first().textContent().catch(() => '?')), b };
}
/** 손짓 하나 — (dx, dy) 만큼 여러 걸음에 걸쳐 민다. */
async function gesture(x, y, dx, dy, steps = 12) {
  await touch('touchStart', x, y);
  await page.waitForTimeout(20);
  for (let i = 1; i <= steps; i++) {
    await touch('touchMove', x + (dx * i) / steps, y + (dy * i) / steps);
    await page.waitForTimeout(16);
  }
  await touch('touchEnd', x + dx, y + dy);
  await page.waitForTimeout(900);
}

await fresh();
const before = await rows();
const { name, b } = await firstRow();
const cx = b.x + b.width - 60, cy = b.y + 26;
console.log(`줄 "${name}" · ${before}건\n`);

// ① 세로로 훑기 — 지워지면 안 되고 스크롤은 돼야 한다
const y0 = await page.evaluate(() => document.querySelector('.scroll').scrollTop);
await gesture(cx, cy, 0, -220);
const y1 = await page.evaluate(() => document.querySelector('.scroll').scrollTop);
console.log(`세로로 훑기    ${(await rows()) === before ? '안 지워짐 ✔' : '지워짐 ✗'} · 스크롤 ${Math.round(y1 - y0)}px ${y1 > y0 ? '✔' : '✗ 안 움직임'}`);

// ② 비스듬히(가로 40 · 세로 90) — 훑는 것으로 봐야 한다
await fresh();
const r2 = await firstRow();
await gesture(r2.b.x + r2.b.width - 60, r2.b.y + 26, -40, -90);
console.log(`비스듬히 훑기  ${(await rows()) === before ? '안 지워짐 ✔' : '지워짐 ✗'}`);

// ③ 가로로 조금(50px) — 안 지워지고 제자리로
await fresh();
const r3 = await firstRow();
await gesture(r3.b.x + r3.b.width - 60, r3.b.y + 26, -50, 0);
console.log(`조금 밀기 50px ${(await rows()) === before ? '안 지워짐 ✔' : '지워짐 ✗'}`);

// ④ 가로로 끝까지(150px) — 지워져야 한다
await fresh();
const r4 = await firstRow();
await touch('touchStart', r4.b.x + r4.b.width - 60, r4.b.y + 26);
await page.waitForTimeout(20);
for (let i = 1; i <= 12; i++) { await touch('touchMove', r4.b.x + r4.b.width - 60 - i * 12.5, r4.b.y + 26); await page.waitForTimeout(16); }
const mid = await page.evaluate(() => {
  const el = document.querySelector('.txn-item.sw');
  return { armed: el.classList.contains('armed'), tf: getComputedStyle(el.querySelector('.sw-body')).transform };
});
await touch('touchEnd', r4.b.x + r4.b.width - 210, r4.b.y + 26);
await page.waitForTimeout(1200);
const after = await rows();
console.log(`끝까지 150px   ${after === before - 1 ? `지워짐 ✔ ${before}→${after}` : `✗ ${before}→${after} (미는 중 armed=${mid.armed} ${mid.tf})`}`);

// ⑤ 줄 안의 버튼을 그냥 누르기 — 라벨이 붙어야 한다
await fresh();
const base5 = await rows();
const row5 = page.locator('.txn-item.sw').filter({ has: page.locator('.ctx3') }).first();
const chip = row5.locator('.ctx3 button').first();
const cb = await chip.boundingBox();
await touch('touchStart', cb.x + cb.width / 2, cb.y + cb.height / 2);
await page.waitForTimeout(60);
await touch('touchEnd', cb.x + cb.width / 2, cb.y + cb.height / 2);
await page.waitForTimeout(900);
const tagged = await row5.locator('.sp-tag.tag-ctx').count();
console.log(`칩 그냥 누르기 ${tagged > 0 ? '라벨 붙음 ✔' : '안 붙음 ✗'} · ${(await rows()) === base5 ? '안 지워짐 ✔' : '지워짐 ✗'}`);

if (errs.length) console.log(`\n오류:\n  ${errs.slice(0, 5).join('\n  ')}`);
await browser.close();
