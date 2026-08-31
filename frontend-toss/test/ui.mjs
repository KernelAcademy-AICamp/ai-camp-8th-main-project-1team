/** 평소 화면에 빨강이 새는지, 밀 때만 드러나는지 — 실제 터치로 본다. */
import { chromium, devices } from 'playwright';

const browser = await chromium.launch();
const ctx = await browser.newContext({ ...devices['Galaxy S9+'], hasTouch: true, isMobile: true });
const page = await ctx.newPage();
await page.goto('http://localhost:8899/demo.html#/transactions');
await page.waitForTimeout(2500);

/** 목록 안에 빨간 화소가 있는지 — 픽셀로 센다. */
async function redPixels(tag) {
  const buf = await page.locator('.scroll').screenshot();
  const { createCanvas, loadImage } = await import('canvas').catch(() => ({}));
  if (!createCanvas) { await page.screenshot({ path: `shots/ui-${tag}.png` }); return null; }
  return null;
}
await page.screenshot({ path: 'shots/ui-rest.png' });

const rows = await page.locator('.txn-item.sw').count();
const leak = await page.evaluate(() => {
  let bad = 0;
  document.querySelectorAll('.txn-item.sw').forEach((el) => {
    const host = el.getBoundingClientRect();
    const body = el.querySelector('.sw-body')?.getBoundingClientRect();
    const del = el.querySelector('.sw-del');
    const vis = del ? Number(getComputedStyle(del).opacity) : 0;
    // 몸통이 껍데기보다 짧으면 그만큼 뒤가 드러난다.
    if (body && Math.round(host.height - body.height) > 1 && vis > 0) bad++;
  });
  return bad;
});
const visible = await page.evaluate(() =>
  [...document.querySelectorAll('.sw-del')].filter((d) => Number(getComputedStyle(d).opacity) > 0).length);

console.log(`줄 ${rows}개`);
console.log(`평소에 보이는 빨강   ${visible}개  ${visible === 0 ? '✔' : '✗'}`);
console.log(`몸통이 짧아 새는 줄  ${leak}개  ${leak === 0 ? '✔' : '✗'}`);

// 밀 때는 드러나야 한다
const row = page.locator('.txn-item.sw').first();
const box = await row.boundingBox();
const cdp = await ctx.newCDPSession(page);
const cx = box.x + box.width - 60, cy = box.y + box.height / 2;
const touch = (t, x, y) => cdp.send('Input.dispatchTouchEvent', { type: t, touchPoints: t === 'touchEnd' ? [] : [{ x, y }] });
await touch('touchStart', cx, cy);
await page.waitForTimeout(600);
for (let i = 1; i <= 6; i++) { await touch('touchMove', cx - i * 12, cy); await page.waitForTimeout(16); }
await page.screenshot({ path: 'shots/ui-swipe.png' });
const shown = await page.evaluate(() =>
  [...document.querySelectorAll('.sw.armed .sw-del')].filter((d) => Number(getComputedStyle(d).opacity) > 0).length);
console.log(`밀 때 드러나는 빨강  ${shown}개  ${shown === 1 ? '✔' : '✗'}`);
await touch('touchEnd', cx - 72, cy);
await page.waitForTimeout(600);
await browser.close();
