import { chromium, devices } from 'playwright';
const browser = await chromium.launch();
const ctx = await browser.newContext({ ...devices['Galaxy S9+'], hasTouch: true, isMobile: true });
const page = await ctx.newPage();
const cdp = await ctx.newCDPSession(page);
const touch = (t,x,y)=>cdp.send('Input.dispatchTouchEvent',{type:t,touchPoints:t==='touchEnd'?[]:[{x,y}]});
async function fresh(h){ await page.goto('http://localhost:8899/demo.html'+h); await page.reload({waitUntil:'load'}); await page.waitForTimeout(2200); }
const dump = () => page.evaluate(() => document.querySelector('.screen')?.innerText ?? '');
await fresh('#/home'); console.log('--- 지우기 전 ---\n' + await dump());
await fresh('#/transactions');
const b = await page.locator('.txn-item.sw').first().boundingBox();
await touch('touchStart', b.x+b.width-60, b.y+26); await page.waitForTimeout(20);
for(let i=1;i<=12;i++){ await touch('touchMove', b.x+b.width-60-i*14, b.y+26); await page.waitForTimeout(16); }
await touch('touchEnd', b.x+b.width-230, b.y+26); await page.waitForTimeout(1200);
await fresh('#/home'); console.log('\n--- 지운 뒤 ---\n' + await dump());
await browser.close();
