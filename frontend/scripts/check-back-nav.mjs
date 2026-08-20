/**
 * 뒤로가기가 갇히는지 **실제 브라우저로 눌러 보는** 검사.
 *
 * <b>왜 정적 검사로는 못 잡나.</b> 이 앱의 이동은 전부 `session.tsx`의 `go()` 한 곳을 지나고
 * 그 안은 `history.pushState` 한 줄이라, 코드만 읽으면 아무 문제가 없어 보인다. 갇힘은
 * <b>이력 스택과 자동 이동이 만나는 자리</b>에서만 생긴다 — 뒤로가서 도착한 화면의 `useEffect`가
 * 다시 `pushState`를 하면 방금 밟고 온 칸이 파괴되고 같은 자리가 새로 쌓인다. 눌러 봐야 보인다.
 *
 * 실제로 그렇게 갇혀 있었다(2026-08-20 재현). 온보딩을 마친 사용자가 홈에서 뒤로를 누르면
 * 일곱 번째에 `#/home`으로 밀린 뒤 <b>아무리 눌러도 안 움직였다.</b> 브라우저는 탭을 닫으면
 * 되지만 앱에서는 강제종료 말고 빠져나갈 길이 없다.
 *
 * 재는 것:
 *   1. 갇힘        — 온보딩을 마친 이력에서 뒤로를 계속 누르면 결국 사이트를 벗어난다
 *   2. 자동 이동    — 사람이 안 누른 이동은 이력을 쌓지 않는다(boot→walk · loading→ob1 · done→home)
 *   3. 사용자 이동  — 사람이 누른 이동은 이력에 남고 뒤로가면 돌아온다  ← 1·2 를 고치다 이걸 깨면 안 된다
 *   4. 탭 왕복      — 형제 탭을 오가는 것은 이력을 쌓지 않는다
 *
 * 서버가 떠 있어야 한다:  npm run dev  (또는 BASE=http://... 로 지정)
 *   node scripts/check-back-nav.mjs
 */
import { chromium } from 'playwright';

const BASE = (process.env.BASE ?? 'http://localhost:5173').replace(/\/$/, '');

/** 온보딩을 통과한 상태. `addInitScript` 로 **앱 스크립트보다 먼저** 심어야 한다 —
    페이지를 연 뒤에 심으면 `linked` 가 첫 마운트에서 false 로 굳는다. */
const SEED_LINKED = () => {
  localStorage.setItem('mydata_onboarded', 'true');
  localStorage.setItem('demo_user_id', '1');
  localStorage.setItem('auth_token', 'check-back-nav');
};

/**
 * 서버 없이 돈다. **`/api/users/*` 만은 성공시켜야 한다** — 404·401·403 이면
 * `session.tsx` 의 기동 점검이 `resetOnboarding()` 을 불러 연동이 통째로 풀린다.
 * 나머지는 503 이어도 화면이 `ErrorBox` 로 받아 낸다(그렇게 만들어 뒀다).
 */
async function stubApi(ctx, extra = {}) {
  await ctx.route('**/api/**', async (route) => {
    const url = route.request().url();
    for (const [frag, body] of Object.entries(extra)) {
      if (url.includes(frag)) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      }
    }
    if (url.includes('/api/users/')) {
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ id: 1, nickname: '검증-tester' }) });
    }
    return route.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"검사용 스텁"}' });
  });
}

const hashOf = (page) => {
  const u = page.url();
  if (!u.startsWith(BASE)) return '밖';
  const i = u.indexOf('#');
  return i < 0 ? '/' : u.slice(i);
};

/** 뒤로 한 번. 되밀림(자동 이동)까지 볼 수 있게 잠깐 기다린다. */
async function pressBack(page, settleMs = 500) {
  await page.evaluate(() => window.history.back()).catch(() => {});
  await page.waitForTimeout(settleMs);
  return hashOf(page);
}

const fails = [];
const ok = (title, detail = '') => console.log(`  ✓ ${title}${detail ? `  ${detail}` : ''}`);
const no = (title, detail) => { fails.push(title); console.log(`  ✗ ${title}\n      ${detail}`); };

const browser = await chromium.launch();

// ── 1. 갇힘 ────────────────────────────────────────────────────────────
/* 온보딩을 막 마친 사람의 이력을 그대로 만든다. `go()` 가 이력에 하는 일이 `pushState` 하나뿐이라
   같은 스택이 된다 — 화면을 실제로 밟으려면 본인인증·마이데이터 연동이 필요해 서버가 있어야 한다. */
const ONBOARDING_TRAIL = ['boot', 'walk', 'auth', 'connect', 'loading',
  'ob1', 'ob2', 'ob3', 'ob4', 'done', 'home'];
{
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 } });
  await ctx.addInitScript(SEED_LINKED);
  await stubApi(ctx);
  const page = await ctx.newPage();
  page.on('pageerror', () => {});
  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(500);
  await page.evaluate((trail) => {
    // `go()` 가 이력에 남기는 것과 **같은 모양**으로 쌓는다 — 깊이 표시까지 같아야 한다.
    trail.forEach((s, i) => window.history.pushState({ moaDepth: i + 1 }, '', `#/${s}`));
  }, ONBOARDING_TRAIL);
  await page.waitForTimeout(300);

  const trail = [];
  let escaped = false;
  for (let i = 0; i < 16; i++) {
    const at = await pressBack(page);
    trail.push(at);
    if (at === '밖') { escaped = true; break; }
  }
  const pretty = trail.map((h, i) => `뒤로 ${String(i + 1).padStart(2)} → ${h}`).join('\n      ');
  /* 갇힘의 표식: 같은 자리가 계속 나온다. 세 번 연속이면 그 뒤도 같다. */
  const stuck = trail.findIndex((h, i) => i >= 2 && h === trail[i - 1] && h === trail[i - 2]);
  if (!escaped || stuck >= 0) {
    no('1. 갇힘 — 뒤로를 계속 누르면 사이트를 벗어난다',
      `${stuck >= 0 ? `${trail[stuck]} 에서 제자리걸음. ` : ''}16번 눌러도 못 나왔다\n      ${pretty}`);
  } else {
    ok('1. 갇힘 없음', `뒤로 ${trail.length}번에 벗어났다`);
  }
  await ctx.close();
}

// ── 2. 자동 이동은 이력을 쌓지 않는다 ──────────────────────────────────
/* 사람이 안 누른 이동이 이력을 쌓으면, 뒤로가서 그 화면에 도착하는 순간 같은 이동이 또 일어나
   앞으로 되밀린다. 셋 다 `ref` 로 이중 실행을 막고 있지만 **뒤로가기는 재마운트라 ref 가 리셋된다.** */
const AUTO_MOVES = [
  { name: 'boot → walk', from: 'boot', to: '#/walk', wait: 3400, linked: false },
  { name: 'loading → ob1', from: 'loading', to: '#/ob1', wait: 6000, linked: true,
    api: { '/api/analysis': { totalSpent: 0, categories: [], days: 90 },
           '/unclassified': { categories: [], aiEnabled: false, items: [] } } },
  { name: 'done → home', from: 'done', to: '#/home', wait: 7000, linked: true },
];
for (const m of AUTO_MOVES) {
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 } });
  if (m.linked) await ctx.addInitScript(SEED_LINKED);
  await stubApi(ctx, m.api);
  const page = await ctx.newPage();
  page.on('pageerror', () => {});
  await page.goto(`${BASE}/#/${m.from}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(400);
  const before = await page.evaluate(() => window.history.length);
  await page.waitForTimeout(m.wait);
  const after = await page.evaluate(() => window.history.length);
  const at = hashOf(page);
  if (at !== m.to) {
    no(`2. 자동 이동 ${m.name}`, `${m.wait}ms 뒤에도 ${at} 에 있다 — 이동 자체가 안 일어났다(검사 전제가 깨졌다)`);
  } else if (after > before) {
    no(`2. 자동 이동 ${m.name} 이 이력을 쌓는다`,
      `이력 ${before} → ${after}. 뒤로가면 ${m.from} 에 도착했다가 다시 ${m.to} 로 밀린다`);
  } else {
    ok(`2. 자동 이동 ${m.name} 이 이력을 안 쌓는다`, `이력 ${before} 그대로`);
  }
  await ctx.close();
}

// ── 3. 사용자가 누른 이동은 이력에 남는다 (대조군) ─────────────────────
/* 2번을 고치다 이것까지 replace 로 바꾸면 뒤로가기가 이번엔 **너무 멀리** 간다.
   마이 > 설정 > '지킴이 말수 설정' 은 데이터 없이도 늘 떠 있는 줄이라 대조군으로 쓴다. */
{
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 } });
  await ctx.addInitScript(SEED_LINKED);
  await stubApi(ctx);
  const page = await ctx.newPage();
  page.on('pageerror', () => {});
  await page.goto(`${BASE}/#/my`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(800);
  const before = await page.evaluate(() => window.history.length);
  const btn = page.getByRole('button', { name: /지킴이 말수 설정/ });
  if (await btn.count() === 0) {
    no('3. 사용자 이동 (대조군)', '마이 화면에서 대조군 버튼을 못 찾았다 — 검사 전제가 깨졌다');
  } else {
    await btn.first().click();
    await page.waitForTimeout(500);
    const after = await page.evaluate(() => window.history.length);
    const at = hashOf(page);
    const backTo = await pressBack(page);
    if (at !== '#/m-voice' || after !== before + 1) {
      no('3. 사용자 이동이 이력에 안 남는다', `클릭 뒤 ${at}, 이력 ${before} → ${after} (＋1 이어야 한다)`);
    } else if (backTo !== '#/my') {
      no('3. 사용자 이동에서 뒤로가기가 안 돌아온다', `뒤로 눌렀더니 ${backTo} (#/my 여야 한다)`);
    } else {
      ok('3. 사용자 이동은 이력에 남고 뒤로가면 돌아온다', `이력 ${before} → ${after}, 뒤로 → ${backTo}`);
    }
  }
  await ctx.close();
}

// ── 4. 탭 왕복은 이력을 쌓지 않는다 ────────────────────────────────────
/* 홈·리포트·마이는 형제지 부모-자식이 아니다. 왕복이 쌓이면 뒤로가기가 두 탭 사이를 오가느라
   앞 화면으로 못 간다 — 갇히지는 않지만 사실상 못 나가는 것과 같다. */
{
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 } });
  await ctx.addInitScript(SEED_LINKED);
  await stubApi(ctx);
  const page = await ctx.newPage();
  page.on('pageerror', () => {});
  await page.goto(`${BASE}/#/home`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(800);
  const nav = page.locator('nav.tabbar');
  if (await nav.count() === 0) {
    no('4. 탭 왕복', '하단 탭이 안 보인다 — 검사 전제가 깨졌다');
  } else {
    const before = await page.evaluate(() => window.history.length);
    for (let i = 0; i < 3; i++) {
      await nav.getByRole('button', { name: '리포트' }).click();
      await page.waitForTimeout(250);
      await nav.getByRole('button', { name: '홈' }).click();
      await page.waitForTimeout(250);
    }
    const after = await page.evaluate(() => window.history.length);
    if (after > before) {
      no('4. 탭 왕복이 이력을 쌓는다', `홈↔리포트 3회 왕복에 이력 ${before} → ${after}`);
    } else {
      ok('4. 탭 왕복이 이력을 안 쌓는다', `이력 ${before} 그대로`);
    }
  }
  await ctx.close();
}

await browser.close();

console.log(fails.length === 0
  ? '\n뒤로가기 위반 0건\n'
  : `\n뒤로가기 위반 ${fails.length}건\n`);
process.exit(fails.length === 0 ? 0 : 1);
