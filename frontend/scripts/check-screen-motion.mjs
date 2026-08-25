/**
 * 화면 전환 연출이 **실제로 붙는지** 브라우저로 눌러 보는 검사.
 *
 * <b>왜 정적 검사로는 못 잡나.</b> 스타일시트에는 규칙이 <b>이미 있었다</b>
 * (`#s-home.enter .pad>*{animation:homeRise …}`). 그런데 그 클래스를 붙이는 코드가 없어
 * <b>한 줄도 안 걸리고 있었다</b> — CSS 원문을 읽는 검사는 규칙이 있다는 것만 보고 통과시킨다.
 * 프로토타입에서는 `tabEnter()` 가 화면을 바꿀 때마다 붙였는데, React 로 옮기면서 그 자리가
 * 사라졌고 아무도 눈치채지 못했다. 전환이 밋밋해도 오류가 안 나기 때문이다.
 *
 * 재는 것:
 *   1. 진입 표시   — 화면을 바꾸면 `.screen` 에 `enter` 가 붙는다
 *   2. 실제 재생   — 그 화면의 내용에 `animation-name` 이 실제로 계산돼 있다
 *   3. 되풀이     — 떠났다 돌아와도 다시 논다(클래스가 남아 있으면 두 번째부터 안 논다)
 *   4. 움직임 줄이기 — `prefers-reduced-motion: reduce` 면 아무것도 안 논다
 *
 * 서버가 떠 있어야 한다:  npm run dev
 *   node scripts/check-screen-motion.mjs
 */
import { chromium } from 'playwright';

const BASE = (process.env.BASE ?? 'http://localhost:5173').replace(/\/$/, '');

const SEED_LINKED = () => {
  localStorage.setItem('mydata_onboarded', 'true');
  localStorage.setItem('demo_user_id', '1');
  localStorage.setItem('auth_token', 'check-screen-motion');
};

async function stubApi(ctx) {
  await ctx.route('**/api/**', async (route) => {
    const url = route.request().url();
    if (url.includes('/api/users/')) {
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ id: 1, nickname: '검증-tester' }) });
    }
    return route.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"검사용 스텁"}' });
  });
}

/** 그 화면이 지금 진입 연출을 걸고 있는가 — 클래스와 **계산된 애니메이션** 둘 다 본다. */
async function motionOf(page) {
  return page.evaluate(() => {
    const s = document.querySelector('.screen');
    if (!s) return { found: false };
    // 연출은 짧다(.26~.38s). 클래스가 남아 있으면 언제든 읽히지만, 애니메이션 이름은
    // 재생이 끝나면 사라지지 않고 계산값으로 남으므로 둘 다 증거가 된다.
    const kids = [...s.querySelectorAll('.pad>*, .appbar, .rp-seg')].slice(0, 6);
    const names = kids.map((k) => getComputedStyle(k).animationName).filter((n) => n && n !== 'none');
    return { found: true, id: s.id || '(id없음)', enter: s.classList.contains('enter'), animated: names.length };
  });
}

const problems = [];
const note = (m) => { problems.push(m); console.log(`  ✗ ${m}`); };
const ok = (m) => console.log(`  · ${m}`);

const browser = await chromium.launch();
const ctx = await browser.newContext();
await ctx.addInitScript(SEED_LINKED);
await stubApi(ctx);
const page = await ctx.newPage();

// ── 1·2. 화면마다 진입 표시가 붙고 실제로 논다 ───────────────────────────────
console.log('\n[1·2] 화면 전환마다 진입 연출이 붙는가');
/**
 * <b>API 가 없어도 Screen 을 그리는 화면만 고른다.</b> 도감·포인트샵처럼 데이터가 있어야
 * 여는 화면은 스텁이 503 이면 `ErrorBox` 를 그리는데, 그건 `Screen` <b>밖</b>이라 연출과
 * 무관하다. 여기서 재려는 것은 "전환에 연출이 붙는가" 이지 "데이터를 못 받으면 어떻게 되는가"
 * 가 아니다 — 후자는 화면마다 다르게 정해 둔 별개의 설계다.
 */
const SCREENS = ['home', 'report', 'my', 'transactions', 'notifications'];
for (const id of SCREENS) {
  await page.goto(`${BASE}/#/${id}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(120);
  const m = await motionOf(page);
  if (!m.found) { note(`${id}: .screen 을 못 찾았다`); continue; }
  if (!m.enter) { note(`${id}: enter 가 안 붙었다 — 전환이 밋밋하다`); continue; }
  if (m.animated === 0) { note(`${id}: enter 는 붙었는데 애니메이션이 하나도 안 걸렸다`); continue; }
  ok(`${id} (${m.id}) — enter · 움직이는 요소 ${m.animated}개`);
}

// ── 3. 떠났다 돌아와도 다시 논다 ─────────────────────────────────────────────
console.log('\n[3] 떠났다 돌아와도 다시 노는가');
await page.goto(`${BASE}/#/home`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(600);            // 연출이 끝나도록 기다린다
await page.goto(`${BASE}/#/my`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(120);
await page.goto(`${BASE}/#/home`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(120);
const again = await motionOf(page);
if (!again.enter || again.animated === 0) note('두 번째 진입에서 안 논다 — 클래스를 지웠다 붙이는 자리가 빠졌다');
else ok(`두 번째 진입에서도 논다 — 움직이는 요소 ${again.animated}개`);

// ── 4. 움직임을 줄이겠다고 하면 안 논다 ──────────────────────────────────────
console.log('\n[4] prefers-reduced-motion 을 지키는가');
const calm = await browser.newContext({ reducedMotion: 'reduce' });
await calm.addInitScript(SEED_LINKED);
await stubApi(calm);
const calmPage = await calm.newPage();
await calmPage.goto(`${BASE}/#/home`, { waitUntil: 'domcontentloaded' });
await calmPage.waitForTimeout(150);
const quiet = await motionOf(calmPage);
if (quiet.animated > 0) note(`움직임을 줄이라고 했는데 ${quiet.animated}개가 움직인다`);
else ok('움직임을 줄이면 아무것도 안 논다');

await browser.close();

console.log(problems.length === 0
  ? '\n화면 전환 연출 — 문제 없음\n'
  : `\n화면 전환 연출 — 문제 ${problems.length}건\n`);
process.exit(problems.length === 0 ? 0 : 1);
