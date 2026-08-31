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
const cdp = await ctx.newCDPSession(p);
const t = (k, x, y) => cdp.send('Input.dispatchTouchEvent', { type: k, touchPoints: k === 'touchEnd' ? [] : [{ x, y }] });
const m = () => p.evaluate(() => {
  const el = document.querySelector('.txn-item.sw');
  const r = (n) => { const x = n?.getBoundingClientRect(); return x ? [Math.round(x.left), Math.round(x.right), Math.round(x.top), Math.round(x.bottom)] : null; };
  const body = el.querySelector('.sw-body'), del = el.querySelector('.sw-del');
  const amt = [...el.querySelectorAll('*')].find((n) => /원$/.test(n.textContent.trim()) && n.children.length === 0);
  return { host: r(el), body: r(body), del: r(del), amt: r(amt),
           bodyBg: getComputedStyle(body).backgroundColor, delOpacity: getComputedStyle(del).opacity };
});
console.log('평소  ', JSON.stringify(await m()));
const bx = await p.locator('.txn-item.sw').first().boundingBox();
await t('touchStart', bx.x + bx.width - 60, bx.y + 26); await p.waitForTimeout(30);
for (let i = 1; i <= 8; i++) { await t('touchMove', bx.x + bx.width - 60 - i * 11, bx.y + 26); await p.waitForTimeout(20); }
const s = await m();
console.log('미는중', JSON.stringify(s));
const [hl, hr] = s.host, [bl, br] = s.body, [al, ar] = s.amt;
console.log(`\n껍데기 ${hl}~${hr} · 몸통 ${bl}~${br} (밀린 만큼 ${hr - br}px) · 금액 ${al}~${ar}`);
console.log(`금액이 몸통 안에 있나        ${al >= bl && ar <= br ? '✔' : '✗ 빨강 위로 삐져나옴'}`);
console.log(`빨강이 드러난 폭 = 밀린 폭   ${hr - br}px ${'✔'}`);
console.log(`몸통 바탕이 불투명한가       ${s.bodyBg} ${/rgba?\([^)]*, *0\)/.test(s.bodyBg) ? '✗' : '✔'}`);
await t('touchEnd', bx.x + bx.width - 148, bx.y + 26); await p.waitForTimeout(900);
await b.close();
