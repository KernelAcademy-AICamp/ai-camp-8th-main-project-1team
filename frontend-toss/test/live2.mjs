/** 실제 번들에 대고 <b>모질게</b> 시험한다 — 빈 상태, 취소, 필터, 추가 흐름, 되묻기. */
import { chromium, devices } from 'playwright';
import { readFileSync } from 'fs';

const URL = 'http://localhost:8900/index.html';
const seed = readFileSync('test/seed.json', 'utf8');
const browser = await chromium.launch();
const ctx = await browser.newContext({ userAgent: devices['Galaxy S9+'].userAgent,
  viewport: { width: 360, height: 780 }, deviceScaleFactor: 3, hasTouch: true, isMobile: true });
const page = await ctx.newPage();
const errs = [];
page.on('pageerror', (e) => errs.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errs.push('console: ' + m.text()); });

let SEED = seed;
await page.addInitScript(() => { window.__seed = null; });
await page.exposeFunction('__getSeed', () => SEED);
await page.addInitScript(() => {
  // 저장소는 각 시험이 직접 정한다
});
const cdp = await ctx.newCDPSession(page);
const touch = (t, x, y) => cdp.send('Input.dispatchTouchEvent', { type: t, touchPoints: t === 'touchEnd' ? [] : [{ x, y }] });
const cancelTouch = () => cdp.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] });
const rows = () => page.evaluate(() => JSON.parse(localStorage.getItem('moa.rows.v1') || '[]'));

let pass = 0, fail = 0;
const say = (l, ok, n = '') => { ok ? pass++ : fail++; console.log(`  ${ok ? '✔' : '✗'} ${l}${n ? '  — ' + n : ''}`); };

/** 저장소를 원하는 값으로 두고 연다. rowsJson 이 null 이면 씨앗을 심는다. */
async function open(hash = '#/transactions', rowsJson) {
  await page.goto(URL + hash);
  await page.evaluate(([r, s]) => {
    localStorage.setItem('mydata_onboarded', 'true');
    if (r !== undefined) { if (r === null) localStorage.removeItem('moa.rows.v1'); else localStorage.setItem('moa.rows.v1', r); localStorage.removeItem('moa.verdict.v1'); localStorage.removeItem('moa.fixed.v1'); }
    else if (!localStorage.getItem('moa.rows.v1')) localStorage.setItem('moa.rows.v1', s);
  }, [rowsJson, SEED]);
  await page.reload({ waitUntil: 'load' });
  await page.waitForTimeout(2200);
}
async function gesture(x, y, dx, dy, steps = 12) {
  await touch('touchStart', x, y); await page.waitForTimeout(20);
  for (let i = 1; i <= steps; i++) { await touch('touchMove', x + dx * i / steps, y + dy * i / steps); await page.waitForTimeout(16); }
  await touch('touchEnd', x + dx, y + dy); await page.waitForTimeout(1000);
}
async function wipe(n = 0) {
  const r = page.locator('.txn-item.sw').nth(n);
  await r.scrollIntoViewIfNeeded(); await page.waitForTimeout(250);
  const b = await r.boundingBox();
  const name = (await r.locator('.nm').first().textContent() ?? '').trim();
  await gesture(b.x + b.width - 60, b.y + 26, -170, 0);
  return name;
}
const text = () => page.evaluate(() => document.querySelector('.screen')?.innerText ?? '');

console.log('\n1. 되묻기 (원래 핫픽스)');
{
  // 사전에 없는 가게 하나만 두고 연다
  const one = JSON.stringify([{ id: 'x1', paymentId: 'x1', merchant: '찬스돔성인나이트', merchantName: '찬스돔성인나이트',
    amount: 1000000, date: '2026-08-30T21:00:00', category2: null }]);
  await open('#/home', one);
  const t = await text();
  say('모르는 가게 한 건만 있어도 홈이 뜬다', t.includes('원'), t.split('\n').slice(0, 6).join(' | '));
  await open('#/transactions', undefined);
  const deck = await page.locator('.txn-item.sw .ctx3').count();
  say('내역에 되묻기 칩이 뜬다', deck > 0, `${deck}줄`);
  const cb = await page.locator('.ctx3 button').nth(1).boundingBox();
  await touch('touchStart', cb.x + cb.width / 2, cb.y + cb.height / 2); await page.waitForTimeout(60);
  await touch('touchEnd', cb.x + cb.width / 2, cb.y + cb.height / 2); await page.waitForTimeout(1200);
  const v = await page.evaluate(() => JSON.parse(localStorage.getItem('moa.verdict.v1') || '{}'));
  say("'새는 돈이었어요' 가 저장된다", Object.values(v)[0] === 'WASTE', JSON.stringify(v));
  await open('#/home', undefined);
  const h = await text();
  say('홈 낭비에 잡힌다', /1,000,000원/.test(h), h.split('\n').slice(3, 9).join(' | '));
}

console.log('\n2. 빈 상태');
{
  await open('#/transactions', JSON.stringify([{ id: 'z1', paymentId: 'z1', merchant: '더벤티', merchantName: '더벤티',
    amount: 13500, date: '2026-08-29T10:00:00', category2: null }]));
  const gone = await wipe(0);
  say('마지막 한 건을 지울 수 있다', (await rows()).length === 0, `"${gone}"`);
  const t = await text();
  say('다 지워도 내역 화면이 깨지지 않는다', t.length > 5 && !/undefined|NaN/.test(t), t.split('\n').slice(0, 4).join(' | '));
  await open('#/home', undefined);
  const h = await text();
  say('다 지워도 홈이 깨지지 않는다', h.length > 5 && !/undefined|NaN/.test(h), h.split('\n').slice(0, 6).join(' | '));
  await open('#/report', undefined);
  const r = await text();
  say('다 지워도 리포트가 깨지지 않는다', r.length > 5 && !/undefined|NaN/.test(r), r.split('\n').slice(0, 4).join(' | '));
}

console.log('\n3. 손짓이 끊길 때');
{
  await open('#/transactions', SEED);
  const n = (await rows()).length;
  const b = await page.locator('.txn-item.sw').first().boundingBox();
  await touch('touchStart', b.x + b.width - 60, b.y + 26); await page.waitForTimeout(20);
  for (let i = 1; i <= 10; i++) { await touch('touchMove', b.x + b.width - 60 - i * 15, b.y + 26); await page.waitForTimeout(16); }
  await cancelTouch(); await page.waitForTimeout(900);
  const st = await page.evaluate(() => {
    const el = document.querySelector('.txn-item.sw');
    return { armed: el.classList.contains('armed'), tf: getComputedStyle(el.querySelector('.sw-body')).transform,
             red: Number(getComputedStyle(el.querySelector('.sw-del')).opacity) };
  });
  say('밀다가 취소되면 제자리로', !st.armed && st.tf === 'none' && st.red === 0, `armed=${st.armed} tf=${st.tf} 빨강=${st.red}`);
  say('취소돼도 안 지워진다', (await rows()).length === n);
  const after = await wipe(0);
  say('취소 뒤에도 다음 손짓이 먹는다', (await rows()).length === n - 1, `"${after}"`);
}

console.log('\n4. 지운 뒤 이어서');
{
  await open('#/transactions', SEED);
  const n = (await rows()).length;
  for (let i = 0; i < 5; i++) await wipe(0);
  say('연달아 다섯 건', (await rows()).length === n - 5);
  // 지운 직후 스크롤이 되는가
  const y0 = await page.evaluate(() => document.querySelector('.scroll').scrollTop);
  const b = await page.locator('.txn-item.sw').first().boundingBox();
  await gesture(b.x + b.width / 2, b.y + 26, 0, -260, 8);
  const y1 = await page.evaluate(() => document.querySelector('.scroll').scrollTop);
  say('지운 직후에도 스크롤된다', y1 > y0 && (await rows()).length === n - 5, `${Math.round(y1 - y0)}px`);
}

console.log('\n5. 찾기·달력과 함께');
{
  await open('#/transactions', SEED);
  const n = (await rows()).length;
  await page.locator('.cal-search').first().click().catch(() => {});
  await page.waitForTimeout(500);
  const box = page.locator('input').first();
  if (await box.count()) {
    await box.fill('스타벅스'); await page.waitForTimeout(900);
    const hits = await page.locator('.txn-item.sw').count();
    say('찾기가 걸러낸다', hits > 0 && hits < n, `${hits}줄`);
    if (hits > 0) { const g = await wipe(0); say('찾기 결과에서도 지워진다', (await rows()).length === n - 1, `"${g}"`); }
  } else say('찾기 입력칸을 찾음', false, '못 찾음');
}

// 추가 흐름은 test/add.mjs 가 끝까지(저장·되묻기까지) 본다.

console.log('\n6. 마무리');
say('JS 오류 0건', errs.length === 0, errs.slice(0, 4).join(' | '));
console.log(`\n${fail === 0 ? '모두 통과' : `실패 ${fail}건`} · ${pass}/${pass + fail}\n`);
await browser.close();
process.exit(fail ? 1 : 0);
