import { chromium, devices } from 'playwright';
const browser = await chromium.launch();
const ctx = await browser.newContext({ ...devices['Galaxy S9+'], hasTouch: true, isMobile: true });
const page = await ctx.newPage();
const errs = []; page.on('pageerror', (e) => errs.push(String(e)));
const cdp = await ctx.newCDPSession(page);
const touch = (t, x, y) => cdp.send('Input.dispatchTouchEvent', { type: t, touchPoints: t === 'touchEnd' ? [] : [{ x, y }] });
async function fresh(h='#/transactions'){ await page.goto('http://localhost:8899/demo.html'+h); await page.reload({waitUntil:'load'}); await page.waitForTimeout(2200); }
async function gesture(x,y,dx,dy,steps=12){ await touch('touchStart',x,y); await page.waitForTimeout(20);
  for(let i=1;i<=steps;i++){ await touch('touchMove',x+dx*i/steps,y+dy*i/steps); await page.waitForTimeout(16);} await touch('touchEnd',x+dx,y+dy); await page.waitForTimeout(1000); }
const snap = () => page.evaluate(() => {
  const t = document.body.innerText;
  const won = [...t.matchAll(/([\d,]{3,})\s*원/g)].map((m) => Number(m[1].replace(/,/g, '')));
  return { won, head: t.split('\n').slice(0, 12).join(' | ') };
});

await fresh('#/home'); const h1 = await snap();
await fresh('#/report'); const p1 = await snap();
console.log('홈   :', h1.head);
console.log('리포트:', p1.won.slice(0,5).join(' / '));

// 첫 줄 금액을 알아내고 지운다
await fresh();
const first = await page.evaluate(() => {
  const r = JSON.parse(localStorage.getItem('moa.rows.v1')||'[]');
  return { n: r.length, sum: r.reduce((a,b)=>a+(b.amount||0),0) };
});
const row = page.locator('.txn-item.sw').first();
const amtTxt = await row.innerText();
const b = await row.boundingBox();
await gesture(b.x + b.width - 60, b.y + 26, -170, 0);
const after = await page.evaluate(() => { const r = JSON.parse(localStorage.getItem('moa.rows.v1')||'[]');
  return { n: r.length, sum: r.reduce((a,b)=>a+(b.amount||0),0) }; });

await fresh('#/home'); const h2 = await snap();
await fresh('#/report'); const p2 = await snap();
console.log(`\n지운 줄: ${amtTxt.replace(/\n/g,' ').slice(0,40)}`);
console.log(`저장소 : ${first.n}건 ${first.sum.toLocaleString()}원 → ${after.n}건 ${after.sum.toLocaleString()}원 (차 ${(first.sum-after.sum).toLocaleString()}원)`);
console.log('홈   :', h2.head);
console.log('리포트:', p2.won.slice(0,5).join(' / '));
console.log(`\nJS 오류 ${errs.length}건`);
await browser.close();
