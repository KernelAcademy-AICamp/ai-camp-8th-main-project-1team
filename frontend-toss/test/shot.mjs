import { chromium, devices } from 'playwright';
import { readFileSync } from 'fs';
const seed = readFileSync('test/seed.json', 'utf8');
const b = await chromium.launch();
const ctx = await b.newContext({ userAgent: devices['Galaxy S9+'].userAgent,
  viewport: { width: 360, height: 780 }, deviceScaleFactor: 3, hasTouch: true, isMobile: true });
const p = await ctx.newPage();
await p.addInitScript(([s]) => { try {
  if (!localStorage.getItem('moa.rows.v1')) localStorage.setItem('moa.rows.v1', s);
  localStorage.setItem('mydata_onboarded', 'true'); } catch {} }, [seed]);
await p.goto('http://localhost:8900/index.html#/transactions');
await p.reload({ waitUntil: 'load' }); await p.waitForTimeout(2500);
await p.screenshot({ path: 'shots/실앱-평소.png' });
const cdp = await ctx.newCDPSession(p);
const t = (k, x, y) => cdp.send('Input.dispatchTouchEvent', { type: k, touchPoints: k === 'touchEnd' ? [] : [{ x, y }] });
const r = p.locator('.txn-item.sw').first(); const bx = await r.boundingBox();
await t('touchStart', bx.x + bx.width - 60, bx.y + 26); await p.waitForTimeout(30);
for (let i = 1; i <= 8; i++) { await t('touchMove', bx.x + bx.width - 60 - i * 11, bx.y + 26); await p.waitForTimeout(20); }
await p.screenshot({ path: 'shots/실앱-미는중.png' });
await t('touchEnd', bx.x + bx.width - 148, bx.y + 26); await p.waitForTimeout(800);
await p.screenshot({ path: 'shots/실앱-놓은뒤.png' });
console.log('찍음');
await b.close();
