import { chromium, devices } from 'playwright';
import { readFileSync } from 'fs';
const seed = readFileSync('test/seed.json', 'utf8');
const b = await chromium.launch();
const ctx = await b.newContext({ userAgent: devices['Galaxy S9+'].userAgent,
  viewport: { width: 360, height: 780 }, deviceScaleFactor: 3, hasTouch: true, isMobile: true });
const p = await ctx.newPage();
const errs = []; p.on('pageerror', (e) => errs.push(String(e)));
await p.goto('http://localhost:8900/index.html#/m-record');
await p.evaluate((s) => { localStorage.setItem('mydata_onboarded','true'); localStorage.setItem('moa.rows.v1', s);
  localStorage.removeItem('moa.verdict.v1'); }, seed);
await p.reload({ waitUntil: 'load' }); await p.waitForTimeout(2200);
const scr = p.locator('.screen');           // 하단 탭은 .screen 바깥이다
const txt = () => p.evaluate(() => document.querySelector('.screen')?.innerText.split('\n').filter(Boolean).slice(0,8).join(' | ') ?? '');
let pass = 0, fail = 0; const say = (l, ok, n='') => { ok?pass++:fail++; console.log(`  ${ok?'✔':'✗'} ${l}${n?'  — '+n:''}`); };

const n0 = await p.evaluate(() => JSON.parse(localStorage.getItem('moa.rows.v1')||'[]').length);
await p.locator('input').first().fill('테스트가게'); await p.waitForTimeout(400);
await scr.locator('button').filter({ hasText: /^다음$/ }).first().click(); await p.waitForTimeout(800);
for (const k of ['1','2','0','0','0']) { await p.locator('.gs-kp button').filter({ hasText: new RegExp(`^${k}$`) }).first().click(); await p.waitForTimeout(120); }
say('금액이 찍힌다', (await p.locator('input.gs-amt-in').inputValue()) === '12,000', await p.locator('input.gs-amt-in').inputValue());
await p.screenshot({ path: 'shots/추가-금액.png' });
await scr.locator('button').filter({ hasText: /^다음$/ }).first().click(); await p.waitForTimeout(800);
say('3단계로 넘어간다', (await txt()).includes('3/3'), await txt());
await scr.locator('button').filter({ hasText: /^오늘$/ }).first().click(); await p.waitForTimeout(400);
console.log('  3단계 화면:', await txt());
const btns = await scr.locator('button').evaluateAll((els) => els.map((e) => e.innerText.trim()));
console.log('  화면 안 버튼:', btns.join(' · '));
const done = scr.locator('button').filter({ hasText: /^추가$/ }).last();
say('마무리 버튼이 눌린다', !(await done.isDisabled()));
await done.click(); await p.waitForTimeout(2000);
const rows = await p.evaluate(() => JSON.parse(localStorage.getItem('moa.rows.v1')||'[]'));
const mine = rows.filter((r) => r.merchant === '테스트가게');
say('저장된다', rows.length === n0 + 1 && mine.length === 1, `${n0}→${rows.length} · ${JSON.stringify(mine[0])}`);
say('금액이 맞다', mine[0]?.amount === 12000, String(mine[0]?.amount));
console.log('  추가 뒤 화면:', await txt());
await p.screenshot({ path: 'shots/추가-끝.png' });
// 내역에서 보이고 지워지는가
await p.goto('http://localhost:8900/index.html#/transactions'); await p.reload({waitUntil:'load'}); await p.waitForTimeout(2200);
const found = await p.locator('.txn-item.sw').filter({ hasText: '테스트가게' }).count();
say('내역에 뜬다', found === 1, `${found}줄`);
say('JS 오류 0건', errs.length === 0, errs.slice(0,3).join(' | '));
console.log(`\n${fail===0?'모두 통과':`실패 ${fail}건`} · ${pass}/${pass+fail}\n`);
await b.close(); process.exit(fail?1:0);
