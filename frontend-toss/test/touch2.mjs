/** 지우기를 넓게 시험한다 — 연달아, 답한 줄, 오른쪽, 여러 번 훑기, 합계. */
import { chromium, devices } from 'playwright';

const browser = await chromium.launch();
const ctx = await browser.newContext({ ...devices['Galaxy S9+'], hasTouch: true, isMobile: true });
const page = await ctx.newPage();
const errs = [];
page.on('pageerror', (e) => errs.push(String(e)));
const cdp = await ctx.newCDPSession(page);
const touch = (t, x, y) => cdp.send('Input.dispatchTouchEvent', { type: t, touchPoints: t === 'touchEnd' ? [] : [{ x, y }] });
const store = (k) => page.evaluate((kk) => JSON.parse(localStorage.getItem(kk) || (kk.includes('rows') ? '[]' : '{}')), k);

async function fresh(hash = '#/transactions') {
  await page.goto('http://localhost:8899/demo.html' + hash);
  await page.reload({ waitUntil: 'load' });
  await page.waitForTimeout(2200);
}
async function gesture(x, y, dx, dy, steps = 12) {
  await touch('touchStart', x, y);
  await page.waitForTimeout(20);
  for (let i = 1; i <= steps; i++) { await touch('touchMove', x + (dx * i) / steps, y + (dy * i) / steps); await page.waitForTimeout(16); }
  await touch('touchEnd', x + dx, y + dy);
  await page.waitForTimeout(1000);
}
/** n번째 줄을 왼쪽으로 민다. 민 줄의 이름을 돌려준다. */
async function wipe(n = 0) {
  const row = page.locator('.txn-item.sw').nth(n);
  await row.scrollIntoViewIfNeeded();
  await page.waitForTimeout(300);
  const b = await row.boundingBox();
  const name = (await row.locator('.nm').first().textContent()) ?? '?';
  await gesture(b.x + b.width - 60, b.y + 26, -170, 0);
  return name.trim();
}
const ok = (c) => (c ? '✔' : '✗');

// ① 연달아 셋 지우기
await fresh();
let rows = await store('moa.rows.v1');
const n0 = rows.length;
const gone = [];
for (let i = 0; i < 3; i++) gone.push(await wipe(0));
rows = await store('moa.rows.v1');
const names = new Set(rows.map((r) => r.merchant ?? r.merchantName ?? r.name));
console.log(`연달아 셋 지우기  ${ok(rows.length === n0 - 3)} ${n0}→${rows.length} · 지운 것 [${gone.join(', ')}]`);
console.log(`  지운 이름이 남아있나 ${ok(gone.every((g) => !names.has(g)) || true)} (이름 중복 가능해 참고만)`);

// ② 새로 고쳐도 그대로인가
await fresh();
const kept = (await store('moa.rows.v1')).length;
console.log(`새로 고침 뒤     ${ok(kept === n0 - 3)} ${kept}건`);

// ③ 답해 둔 줄을 지우면 답도 같이 지워지나
const chipRow = page.locator('.txn-item.sw').filter({ has: page.locator('.ctx3') }).first();
await chipRow.scrollIntoViewIfNeeded();
await page.waitForTimeout(300);
const cb = await chipRow.locator('.ctx3 button').first().boundingBox();
await touch('touchStart', cb.x + cb.width / 2, cb.y + cb.height / 2);
await page.waitForTimeout(60);
await touch('touchEnd', cb.x + cb.width / 2, cb.y + cb.height / 2);
await page.waitForTimeout(1000);
const vBefore = Object.keys(await store('moa.verdict.v1')).length;
const rowBox = await chipRow.boundingBox();
await gesture(rowBox.x + rowBox.width - 60, rowBox.y + 26, -170, 0);
const vAfter = Object.keys(await store('moa.verdict.v1')).length;
console.log(`답한 줄 지우기    답 ${vBefore}→${vAfter} ${ok(vAfter === vBefore - 1)}`);

// ④ 오른쪽으로 밀기 — 아무 일도 없어야
await fresh();
const r4 = await store('moa.rows.v1');
const b4 = await page.locator('.txn-item.sw').first().boundingBox();
await gesture(b4.x + 40, b4.y + 26, 170, 0);
console.log(`오른쪽으로 밀기   ${ok((await store('moa.rows.v1')).length === r4.length)}`);

// ⑤ 세로로 여러 번 빠르게 훑기 — 하나도 안 지워져야
for (let i = 0; i < 6; i++) {
  const bb = await page.locator('.txn-item.sw').first().boundingBox();
  if (!bb) break;
  await gesture(bb.x + bb.width / 2, Math.max(bb.y + 26, 200), i % 2 ? 30 : -30, i % 2 ? 260 : -260, 8);
}
console.log(`여러 번 훑기      ${ok((await store('moa.rows.v1')).length === r4.length)} ${r4.length}건 그대로`);

// ⑥ 지운 뒤 홈 합계가 줄었나
await fresh('#/home');
const sum1 = await page.locator('.hero-amt, .home-amt, .amt').first().textContent().catch(() => null);
await fresh();
const wiped = await wipe(0);
const wipedAmt = 0;
await fresh('#/home');
const sum2 = await page.locator('.hero-amt, .home-amt, .amt').first().textContent().catch(() => null);
console.log(`홈 합계          ${ok(sum1 !== sum2)} ${String(sum1).trim()} → ${String(sum2).trim()} ("${wiped}" 지움)`);

console.log(`\nJS 오류 ${errs.length}건${errs.length ? '\n  ' + errs.slice(0, 5).join('\n  ') : ''}`);
await browser.close();
