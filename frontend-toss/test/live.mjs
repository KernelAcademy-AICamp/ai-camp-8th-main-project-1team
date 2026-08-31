/**
 * <b>토스에 올라가는 번들 그 자체</b>를 띄워 검사한다.
 *
 * 데모 빌드(`demo-dist`)로만 시험했다가, 정작 `.ait` 에 담기는 `dist` 가 낡은 채로 나간 적이
 * 있다(2026-08-30). 그래서 여기서는 `dist/index.html` 을 그대로 열고, 데모 진입점이 하던
 * 두 가지(예시 채우기·`mydata_onboarded`)만 저장소에 미리 심는다.
 *
 *   node test/live.mjs            → dist (기본, 8900)
 *   node test/live.mjs demo       → demo-dist (8899)
 */
import { chromium, devices } from 'playwright';
import { readFileSync } from 'fs';

const useDemo = process.argv[2] === 'demo';
const URL = useDemo ? 'http://localhost:8899/demo.html' : 'http://localhost:8900/index.html';
const seed = readFileSync('test/seed.json', 'utf8');

/** 기기는 `DEVICE=` 로 고른다. 신고가 들어온 기기(1080x2340)는 CSS 로 360x780 이다. */
const CUSTOM = {
  '갤럭시 360x780': { viewport: { width: 360, height: 780 }, deviceScaleFactor: 3 },
  '작은 폰 320x568': { viewport: { width: 320, height: 568 }, deviceScaleFactor: 2 },
};
const dev = process.env.DEVICE || 'Galaxy S9+';
const profile = CUSTOM[dev] ?? devices[dev];
if (!profile) { console.error(`모르는 기기: ${dev}`); process.exit(2); }
const browser = await chromium.launch();
const ctx = await browser.newContext({
  userAgent: devices['Galaxy S9+'].userAgent, ...profile, hasTouch: true, isMobile: true });
const page = await ctx.newPage();
const errs = [], net = [];
page.on('pageerror', (e) => errs.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errs.push('console: ' + m.text()); });
page.on('request', (r) => { if (!r.url().startsWith(URL.replace(/\/[^/]*$/, ''))) net.push(r.url()); });
await page.addInitScript(([s]) => {
  try {
    if (!localStorage.getItem('moa.rows.v1')) localStorage.setItem('moa.rows.v1', s);
    localStorage.setItem('mydata_onboarded', 'true');
  } catch { /* 막힌 저장소 */ }
}, [seed]);

const cdp = await ctx.newCDPSession(page);
const touch = (t, x, y) => cdp.send('Input.dispatchTouchEvent', { type: t, touchPoints: t === 'touchEnd' ? [] : [{ x, y }] });
const rows = () => page.evaluate(() => JSON.parse(localStorage.getItem('moa.rows.v1') || '[]'));
const verdicts = () => page.evaluate(() => Object.keys(JSON.parse(localStorage.getItem('moa.verdict.v1') || '{}')).length);

let pass = 0, fail = 0;
const say = (label, good, note = '') => {
  good ? pass++ : fail++;
  console.log(`  ${good ? '✔' : '✗'} ${label}${note ? '  — ' + note : ''}`);
};

async function open(hash = '#/transactions') {
  await page.goto(URL + hash);
  await page.reload({ waitUntil: 'load' });   // 해시만 같은 주소는 새로 고쳐지지 않는다
  await page.waitForTimeout(2200);
}
async function gesture(x, y, dx, dy, { steps = 12, hold = 0 } = {}) {
  await touch('touchStart', x, y);
  await page.waitForTimeout(20 + hold);
  for (let i = 1; i <= steps; i++) { await touch('touchMove', x + dx * i / steps, y + dy * i / steps); await page.waitForTimeout(16); }
  await touch('touchEnd', x + dx, y + dy);
  await page.waitForTimeout(1000);
}
async function rowBox(n = 0) {
  const r = page.locator('.txn-item.sw').nth(n);
  await r.scrollIntoViewIfNeeded();
  await page.waitForTimeout(250);
  return { box: await r.boundingBox(), name: (await r.locator('.nm').first().textContent() ?? '').trim(), el: r };
}
/** 오른쪽 끝을 잡고 왼쪽으로 민다. */
const wipe = async (n, dx = -170, opt) => { const { box, name } = await rowBox(n); await gesture(box.x + box.width - 60, box.y + 26, dx, 0, opt); return name; };

console.log(`\n대상: ${URL} · 기기 ${dev} ${profile.viewport.width}x${profile.viewport.height}\n`);

/* ── 1. 화면이 열리는가 ───────────────────────────────── */
console.log('1. 네 화면');
for (const [h, mark] of [['#/home', '이번 달 쓴 돈'], ['#/report', null], ['#/transactions', '소비 내역'], ['#/m-record', null]]) {
  await open(h);
  const txt = await page.evaluate(() => document.querySelector('.screen')?.innerText ?? '');
  say(h, txt.length > 20 && (!mark || txt.includes(mark)), `${txt.split('\n')[0] || '(빈 화면)'}`);
}

/* ── 2. 평소 화면에 빨강이 새지 않는가 ─────────────────── */
console.log('\n2. 평소 화면');
await open();
const look = await page.evaluate(() => {
  let leak = 0, shown = 0;
  document.querySelectorAll('.txn-item.sw').forEach((el) => {
    const host = el.getBoundingClientRect(), body = el.querySelector('.sw-body')?.getBoundingClientRect();
    const del = el.querySelector('.sw-del');
    const vis = del ? Number(getComputedStyle(del).opacity) : 0;
    if (vis > 0) shown++;
    if (body && Math.round(host.height - body.height) > 1) leak++;
  });
  return { leak, shown, n: document.querySelectorAll('.txn-item.sw').length };
});
say(`빨간 바탕이 안 보인다 (${look.n}줄)`, look.shown === 0, `보이는 것 ${look.shown}개`);
say('몸통이 껍데기를 다 덮는다', look.leak === 0, `짧은 줄 ${look.leak}개`);

/* ── 3. 손짓 ─────────────────────────────────────────── */
console.log('\n3. 손짓');
const n0 = (await rows()).length;

await open();
const sc0 = await page.evaluate(() => document.querySelector('.scroll').scrollTop);
let b = (await rowBox(0)).box;
await gesture(b.x + b.width - 60, b.y + 26, 0, -240);
const sc1 = await page.evaluate(() => document.querySelector('.scroll').scrollTop);
say('세로로 훑기 — 안 지워지고 스크롤된다', (await rows()).length === n0 && sc1 > sc0, `${Math.round(sc1 - sc0)}px`);

await open();
b = (await rowBox(0)).box;
await gesture(b.x + b.width - 60, b.y + 26, -40, -90);
say('비스듬히(가로40·세로90) — 안 지워진다', (await rows()).length === n0);

await open();
await wipe(0, -50);
const rest = await page.evaluate(() => getComputedStyle(document.querySelector('.sw-body')).transform);
say('조금(50px) 밀고 떼기 — 안 지워지고 제자리', (await rows()).length === n0, `transform ${rest}`);

await open();
b = (await rowBox(0)).box;
await gesture(b.x + 40, b.y + 26, 170, 0);
say('오른쪽으로 밀기 — 아무 일 없다', (await rows()).length === n0);

await open();
b = (await rowBox(0)).box;
await gesture(b.x + b.width - 60, b.y + 26, 0, 0, { steps: 1, hold: 1400 });
say('길게 누르고 떼기 — 아무 일 없다', (await rows()).length === n0);

await open();
b = (await rowBox(0)).box;
await touch('touchStart', b.x + b.width - 60, b.y + 26);
await page.waitForTimeout(1300);
for (let i = 1; i <= 12; i++) { await touch('touchMove', b.x + b.width - 60 - i * 14, b.y + 26); await page.waitForTimeout(16); }
await touch('touchEnd', b.x + b.width - 228, b.y + 26);
await page.waitForTimeout(1000);
say('오래 누른 뒤 밀기 — 그래도 지워진다', (await rows()).length === n0 - 1);

await open();
const n1 = (await rows()).length;
const gone1 = await wipe(0);
say('끝까지(170px) 밀기 — 지워진다', (await rows()).length === n1 - 1, `"${gone1}"`);

for (let i = 0; i < 6; i++) {
  const { box } = await rowBox(0);
  await gesture(box.x + box.width / 2, Math.max(box.y + 26, 220), i % 2 ? 30 : -30, i % 2 ? 280 : -280, { steps: 8 });
}
say('세로로 여섯 번 빠르게 — 하나도 안 지워진다', (await rows()).length === n1 - 1);

/* ── 4. 지우기가 남기는 것 ───────────────────────────── */
console.log('\n4. 지운 뒤');
await open();
const n2 = (await rows()).length;
const three = [];
for (let i = 0; i < 3; i++) three.push(await wipe(0));
say('연달아 셋 지우기', (await rows()).length === n2 - 3, `[${three.join(', ')}]`);
await open();
say('새로 고쳐도 그대로', (await rows()).length === n2 - 3);

const chipRow = page.locator('.txn-item.sw').filter({ has: page.locator('.ctx3') }).first();
await chipRow.scrollIntoViewIfNeeded(); await page.waitForTimeout(250);
const cb = await chipRow.locator('.ctx3 button').first().boundingBox();
await touch('touchStart', cb.x + cb.width / 2, cb.y + cb.height / 2);
await page.waitForTimeout(60);
await touch('touchEnd', cb.x + cb.width / 2, cb.y + cb.height / 2);
await page.waitForTimeout(1000);
const tagged = await chipRow.locator('.sp-tag').count();
say('칩을 누르면 라벨이 붙는다', tagged > 0);
const v0 = await verdicts();
const cr = await chipRow.boundingBox();
await gesture(cr.x + cr.width - 60, cr.y + 26, -170, 0);
say('답한 줄을 지우면 답도 사라진다', (await verdicts()) === v0 - 1, `${v0}→${await verdicts()}`);

/* ── 5. 합계 ─────────────────────────────────────────── */
console.log('\n5. 합계');
const sumOf = (rs) => rs.reduce((a, r) => a + (r.amount || 0), 0);
await open('#/home');
const money = (t) => [...t.matchAll(/([\d,]{3,})원/g)].map((m) => Number(m[1].replace(/,/g, '')));
const home0 = money(await page.evaluate(() => document.querySelector('.screen').innerText))[0];
await open('#/report');
const rep0 = money(await page.evaluate(() => document.querySelector('.screen').innerText))[0];
const store0 = sumOf(await rows());
await open();
const { box: tb } = await rowBox(0);
const amt = await page.evaluate(() => {
  const t = document.querySelector('.txn-item.sw')?.innerText ?? '';
  const m = t.match(/([\d,]+)원/); return m ? Number(m[1].replace(/,/g, '')) : 0;
});
await gesture(tb.x + tb.width - 60, tb.y + 26, -170, 0);
const store1 = sumOf(await rows());
await open('#/home');
const home1 = money(await page.evaluate(() => document.querySelector('.screen').innerText))[0];
await open('#/report');
const rep1 = money(await page.evaluate(() => document.querySelector('.screen').innerText))[0];
say(`저장소가 지운 만큼 준다 (${amt.toLocaleString()}원)`, store0 - store1 === amt, `${store0.toLocaleString()}→${store1.toLocaleString()}`);
say('홈 이번 달 쓴 돈도 같은 만큼', home0 - home1 === amt, `${home0?.toLocaleString()}→${home1?.toLocaleString()}`);
say('리포트 전체 지출도 같은 만큼', rep0 - rep1 === amt, `${rep0?.toLocaleString()}→${rep1?.toLocaleString()}`);

/* ── 6. 바깥으로 나가는 것 ───────────────────────────── */
console.log('\n6. 마무리');
say('바깥 네트워크 요청 0건', net.length === 0, net.slice(0, 3).join(', '));
say('JS 오류 0건', errs.length === 0, errs.slice(0, 3).join(' | '));

console.log(`\n${fail === 0 ? '모두 통과' : `실패 ${fail}건`} · ${pass}/${pass + fail}\n`);
await browser.close();
process.exit(fail === 0 ? 0 : 1);
