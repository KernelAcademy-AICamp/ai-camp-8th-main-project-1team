import { chromium, devices } from 'playwright';
const browser = await chromium.launch();
const ctx = await browser.newContext({ ...devices['Galaxy S9+'], hasTouch: true, isMobile: true });
const page = await ctx.newPage();
page.on('console', (m) => console.log('  [브라우저]', m.text()));
page.on('pageerror', (e) => console.log('  [오류]', String(e)));
const cdp = await ctx.newCDPSession(page);
const touch = (t, x, y) => cdp.send('Input.dispatchTouchEvent', { type: t, touchPoints: t === 'touchEnd' ? [] : [{ x, y }] });

await page.goto('http://localhost:8899/demo.html#/transactions');
await page.waitForTimeout(2200);

// 실제 포인터 이벤트가 줄에 닿는지 본다
await page.evaluate(() => {
  const el = document.querySelector('.txn-item.sw');
  for (const t of ['pointerdown','pointermove','pointerup','pointercancel','click']) {
    el.addEventListener(t, (e) => {
      if (t === 'pointermove' && Math.round(e.clientX) % 40 !== 0) return;
      console.log(`${t} id=${e.pointerId ?? '-'} type=${e.pointerType ?? '-'} x=${Math.round(e.clientX)}`);
    }, true);
  }
});

const r = page.locator('.txn-item.sw').first();
const b = await r.boundingBox();
const cx = b.x + b.width - 60, cy = b.y + 26;
console.log(`줄 x=${Math.round(b.x)}~${Math.round(b.x+b.width)} y=${Math.round(b.y)} · 시작 ${Math.round(cx)},${Math.round(cy)}`);

await touch('touchStart', cx, cy);
await page.waitForTimeout(30);
for (let i = 1; i <= 12; i++) { await touch('touchMove', cx - i * 13, cy); await page.waitForTimeout(16); }
const state = await page.evaluate(() => {
  const el = document.querySelector('.txn-item.sw');
  return { armed: el.classList.contains('armed'), tf: getComputedStyle(el.querySelector('.sw-body')).transform };
});
console.log(`미는 중: armed=${state.armed} transform=${state.tf}`);
await touch('touchEnd', cx - 156, cy);
await page.waitForTimeout(1000);
console.log(`남은 줄 ${await page.evaluate(() => JSON.parse(localStorage.getItem('moa.rows.v1')||'[]').length)}`);
await browser.close();
