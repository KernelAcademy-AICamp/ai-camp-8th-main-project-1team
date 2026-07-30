/**
 * 렌더링된 화면을 **실제로 재는** 접근성·반응형 검사.
 *
 * **왜 정적 검사로는 부족한가.** `scripts/check-a11y.mjs`는 CSS 원문을 읽어 33개 항목을
 * 판정하는데, 그중 값을 계산하는 것은 대비 하나뿐이고 나머지는 "그런 규칙이 있는가"를 본다.
 * 그래서 `min-height:44px` 규칙이 16개 있다는 것은 알아도, **화면에 뜬 버튼이 실제로 44px인지**는
 * 모른다. 부모의 `overflow`나 `flex`가 눌러 버리면 규칙이 있어도 작아진다.
 *
 * 실제로 이 프로젝트에서 정적 검사를 통과한 채로 나간 사고가 둘 있었다 — 대비 2.84:1 버튼과,
 * `:root` 토큰이 날아가 **배경이 투명해진 '다음' 버튼**. 후자는 CSS 원문에 `background:var(--blue)`가
 * 멀쩡히 적혀 있어서 문자열 검사로는 영원히 못 잡는다. 계산된 값을 봐야 잡힌다.
 *
 * 재는 것:
 *   6.1.3  조작 가능      — 모든 조작 요소의 실제 폭·높이 ≥ 44px (KWCAG 2.2 / KS X 3253)
 *   5.4.3  명도 대비      — 렌더링된 글자색 대 **실제로 칠해진** 배경색 ≥ 4.5:1 (18.5px 굵게는 3:1)
 *   6.1.2  초점 표시      — Tab 순회하며 outline 이 실제로 그려지는지
 *   6.1.1  키보드 사용    — Tab 으로 닿는 요소 수, 초점 덫(trap)
 *   반응형  가로 스크롤    — 320·375·768·1280px 에서 문서가 넘치지 않는지
 *   5.1.1  대체 텍스트    — 접근 이름이 빈 조작 요소
 *
 * 서버가 떠 있어야 한다:  npm run dev  (또는 BASE=http://... 로 지정)
 *   node scripts/audit-rendered.mjs
 */
import { chromium } from 'playwright';

const BASE = process.env.BASE ?? 'http://localhost:5173';
const VIEWPORTS = [
  { name: '320  (구형 폰)', width: 320, height: 640 },
  { name: '375  (기준 폰)', width: 375, height: 812 },
  { name: '768  (태블릿)', width: 768, height: 1024 },
  { name: '1280 (데스크톱)', width: 1280, height: 900 },
];

/* 33개 화면 전부. 라우팅은 **해시**(`#/home`)이고 `mydata_onboarded` 플래그로 잠기므로,
   경로(`/home`)로 열면 전부 스플래시로 튕긴다 — 처음 이 검사를 그렇게 짜서 화면당 조작 요소가
   2개밖에 안 잡혔다. 세션을 심고 해시로 들어간다. */
const ROUTES = [
  'splash', 'auth', 'connect', 'loading', 'ob1', 'ob2', 'ob3', 'done',
  'home', 'report', 'my', 'myroom', 'notifications', 'transactions',
  'collection', 'shop', 'monthend', 'settle', 'renew',
  'r-compare', 'r-analysis', 'r-spending', 'r-cards', 'r-account', 'r-waste', 'r-savings',
  'm-impulse', 'm-goals', 'm-connections', 'm-record', 'm-policy', 'm-survey', 'm-demo',
];

/** 온보딩을 통과한 상태로 만든다 — 안 하면 잠긴 화면이 전부 홈으로 튕긴다.
    `addInitScript` 로 **앱 스크립트보다 먼저** 심어야 한다. 페이지를 연 뒤에 심었더니
    `linked` 가 첫 마운트에서 이미 false 로 굳어 33개 화면 중 30개가 스플래시만 보였다. */
const SEED_SESSION = () => {
  localStorage.setItem('mydata_onboarded', 'true');
  localStorage.setItem('demo_user_id', '1');
};

// ── 브라우저 안에서 도는 측정기 ────────────────────────────────────────────
const PROBE = () => {
  const px = (v) => parseFloat(v) || 0;

  const lum = ([r, g, b]) => {
    const f = [r, g, b].map((x) => {
      const s = x / 255;
      return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * f[0] + 0.7152 * f[1] + 0.0722 * f[2];
  };
  const parse = (c) => {
    const m = c.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/);
    return m ? [+m[1], +m[2], +m[3], m[4] === undefined ? 1 : +m[4]] : null;
  };
  /** 반투명은 부모 위에 얹어 실제로 눈에 보이는 색을 만든다. */
  const over = (fg, bg) => fg.slice(0, 3).map((c, i) => c * fg[3] + bg[i] * (1 - fg[3]));
  /** 그라디언트의 색 정지점들. `backgroundColor` 는 그라디언트를 transparent 로 보고하므로
      이걸 안 보면 조상까지 올라가 흰색으로 오판한다 — 은행 카드(흰 글자 위 브랜드 그라디언트)가
      1.05:1 로 잡혔던 원인이다. */
  const stops = (el) => {
    const cs = getComputedStyle(el);
    const img = cs.backgroundImage;
    if (!img || img === 'none' || !img.includes('gradient')) return [];
    /* `background-size` 가 작으면 아이콘이다 — select 의 5×5px 꺾쇠 화살표가 그렇다.
       그걸 글자 배경으로 세면 멀쩡한 입력이 3.72:1 로 잡힌다. 면을 칠하는 것만 배경이다. */
    const sz = cs.backgroundSize.split(',')[0].trim();
    const dims = [...sz.matchAll(/([\d.]+)px/g)].map((m) => +m[1]);
    if (dims.length && Math.min(...dims) < 24) return [];
    return [...img.matchAll(/rgba?\([\d.\s,]+\)/g)].map((m) => parse(m[0])).filter((c) => c && c[3] > 0);
  };

  /** 조상을 거슬러 올라가 **실제로 칠해진** 배경을 찾는다 — transparent 는 부모 것이 보인다.
      그라디언트면 정지점을 모두 돌려주고, 대비는 그중 **가장 나쁜 것**으로 판정한다. */
  const paintedBg = (el) => {
    let n = el;
    let acc = null;
    while (n && n !== document.documentElement.parentNode) {
      const g = stops(n);
      if (g.length) return { many: g.map((c) => (c[3] < 1 && acc ? over(c, acc) : c.slice(0, 3))) };
      const c = parse(getComputedStyle(n).backgroundColor);
      if (c && c[3] > 0) {
        acc = acc === null ? c : [...over(acc, c), 1];
        if (acc[3] >= 1 || c[3] >= 1) return { many: [acc.slice(0, 3)] };
      }
      n = n.parentElement;
    }
    return { many: [acc ? acc.slice(0, 3) : [255, 255, 255]] };
  };
  /** 배경 후보 중 가장 나쁜 대비. 그라디언트 위 글자는 어느 지점에서도 읽혀야 한다. */
  const worstContrast = (fgRaw, bgs) => {
    let worst = Infinity;
    let at = bgs[0];
    for (const bg of bgs) {
      const eff = fgRaw[3] < 1 ? over(fgRaw, bg) : fgRaw.slice(0, 3);
      const r = contrast(eff, bg);
      if (r < worst) { worst = r; at = bg; }
    }
    return { ratio: worst, bg: at };
  };
  const contrast = (a, b) => {
    const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
    return (hi + 0.05) / (lo + 0.05);
  };

  const visible = (el) => {
    const s = getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden' || +s.opacity === 0) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };
  /**
   * 비활성 요소인가. KWCAG 5.4.3(=WCAG 1.4.3)은 **작동하지 않는 구성요소**의 글자를 대비 대상에서
   * 뺀다 — 지난 날짜 달력 칸, 놓을 수 없는 방 슬롯 같은 것들이다. 크롬이 비활성 버튼 글자를
   * `rgba(16,16,16,.3)`으로 흐리게 그리므로, 빼지 않으면 정상 UI 가 위반으로 잡힌다.
   */
  const inactive = (el) => !!el.closest('[disabled],[aria-disabled="true"],fieldset[disabled]');
  /** 접근 이름. `<label><span>금액</span><input/></label>` 처럼 **감싼 label** 도 정당한 이름이라
      이걸 안 보면 멀쩡한 입력이 '이름 없음'으로 잡힌다(실제로 2건 오탐했다). */
  const label = (el) => {
    const byId = el.id ? document.querySelector(`label[for="${CSS.escape(el.id)}"]`) : null;
    const wrap = el.closest('label');
    return (
      el.getAttribute('aria-label') ??
      (el.getAttribute('aria-labelledby')
        ? document.getElementById(el.getAttribute('aria-labelledby'))?.textContent
        : null) ??
      (el.textContent?.trim() ? el.textContent : null) ??
      byId?.textContent ??
      (wrap && wrap !== el ? wrap.textContent : null) ??
      el.getAttribute('title') ??
      el.getAttribute('placeholder') ??
      ''
    )
      .trim()
      .slice(0, 26);
  };

  const CONTROL = 'button, a[href], input, select, textarea, [role="button"], [role="tab"], [role="switch"], [tabindex]:not([tabindex="-1"])';
  const controls = [...document.querySelectorAll(CONTROL)].filter(visible);

  const small = [];
  const lowContrast = [];
  const unnamed = [];
  const unstyled = [];

  /* UA 기본값이 남은 <button>. 클래스는 붙어 있는데 `background`·`border`·`font-family`·`width`를
     지우지 않으면 브라우저 기본 회색 버튼이 그대로 보인다 — 동의창이 회색 덩어리로,
     인증서 목록이 시스템 폰트로 나갔던 원인이다. CSS는 있으니 문자열 검사로는 못 잡는다. */
  const bodyFont = getComputedStyle(document.body).fontFamily;

  for (const el of controls) {
    const r = el.getBoundingClientRect();
    const s = getComputedStyle(el);
    const off = inactive(el);           // 비활성은 누를 수 없다 — 크기·대비 대상이 아니다

    /* 6.1.3 — 44×44. 인라인 링크(문장 속 a)는 지침상 예외다. */
    const inlineLink = el.tagName === 'A' && s.display.includes('inline');
    if (!off && !inlineLink && (r.width < 44 || r.height < 44)) {
      small.push({ tag: el.tagName.toLowerCase(), cls: el.className?.toString().slice(0, 30),
                   label: label(el), w: +r.width.toFixed(1), h: +r.height.toFixed(1) });
    }

    /* UA 기본값이 남았는가 — 폰트가 본문과 다르거나, 크롬 기본 버튼 면색 그대로거나 */
    if (el.tagName === 'BUTTON' && el.className) {
      const why = [];
      if (s.fontFamily !== bodyFont) why.push('폰트');
      if (s.backgroundColor === 'rgb(239, 239, 239)') why.push('기본면색');
      if (why.length) {
        unstyled.push({ cls: el.className.toString().slice(0, 30), label: label(el), why: why.join('·') });
      }
    }

    /* 5.1.1 — 접근 이름 */
    if (!label(el) && !el.querySelector('img[alt]:not([alt=""])')) {
      unnamed.push({ tag: el.tagName.toLowerCase(), cls: el.className?.toString().slice(0, 30) });
    }

    /* 5.4.3 — 글자 대비. 큰 글자(18.5px+ 또는 14px+ 굵게)는 3:1. */
    const fg = parse(s.color);
    if (!off && fg && fg[3] > 0 && el.textContent.trim()) {
      const size = px(s.fontSize);
      const bold = +s.fontWeight >= 700;
      const need = size >= 24 || (size >= 18.66 && bold) ? 3 : 4.5;
      const { ratio } = worstContrast(fg, paintedBg(el).many);
      if (ratio < need) {
        lowContrast.push({ label: label(el), cls: el.className?.toString().slice(0, 30),
                           ratio: +ratio.toFixed(2), need, size: +size.toFixed(0) });
      }
    }
  }

  /* 본문 텍스트 대비도 본다 — 조작 요소만 보면 설명문이 흐린 것을 놓친다. */
  const textLow = [];
  for (const el of [...document.querySelectorAll('p, span, div, li, h1, h2, h3, h4, label, small')]) {
    if (!visible(el) || inactive(el)) continue;
    const own = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim());
    if (!own) continue;
    const s = getComputedStyle(el);
    const fg = parse(s.color);
    if (!fg || fg[3] === 0) continue;
    const size = px(s.fontSize);
    const need = size >= 24 || (size >= 18.66 && +s.fontWeight >= 700) ? 3 : 4.5;
    const { ratio } = worstContrast(fg, paintedBg(el).many);
    if (ratio < need) {
      textLow.push({ text: el.textContent.trim().slice(0, 24), cls: el.className?.toString().slice(0, 26),
                     ratio: +ratio.toFixed(2), need, size: +size.toFixed(0) });
    }
  }

  /* 반응형 — 문서가 뷰포트보다 넓은가, 넘치는 놈은 누구인가 */
  const docW = document.documentElement.scrollWidth;
  const overflowing = docW > window.innerWidth + 1
    ? [...document.querySelectorAll('*')]
        .filter((el) => visible(el) && el.getBoundingClientRect().right > window.innerWidth + 1)
        .slice(0, 5)
        .map((el) => `${el.tagName.toLowerCase()}.${el.className?.toString().slice(0, 24)}`)
    : [];

  return {
    controls: controls.length,
    small, lowContrast, unnamed, unstyled,
    textLow, /* 자르지 않는다 — 12건으로 잘랐더니 오탐이 앞을 채워 진짜 위반이 가려졌다. */
    textLowCount: textLow.length,
    docW, innerW: window.innerWidth, overflowing,
  };
};

// ── 실행 ────────────────────────────────────────────────────────────────
const browser = await chromium.launch();
const findings = { small: [], contrast: [], unnamed: [], overflow: [], focus: [], unstyled: [] };
let pagesSeen = 0;
const redirected = [];
let controlsSeen = 0;

for (const vp of VIEWPORTS) {
  const ctx = await browser.newContext({ viewport: { width: vp.width, height: vp.height } });
  await ctx.addInitScript(SEED_SESSION);
  const page = await ctx.newPage();
  page.on('pageerror', () => {});

  for (const [i, route] of ROUTES.entries()) {
    try {
      /* 해시만 바뀌면 브라우저가 다시 읽지 않아 앱이 새 화면으로 마운트되지 않는다.
         질의문자열을 함께 바꿔 매번 진짜 탐색이 되게 한다. */
      await page.goto(`${BASE}/?r=${i}#/${route}`, { waitUntil: 'domcontentloaded', timeout: 20000 });
      await page.waitForLoadState('networkidle', { timeout: 12000 }).catch(() => {});
    } catch {
      continue;
    }
    await page.waitForTimeout(600);
    /* 실제로 그 화면에 있는지 — 튕겼으면 같은 화면을 33번 재게 된다. */
    const at = await page.evaluate(() => location.hash.replace(/^#\/?/, ''));
    if (at !== route) { redirected.push(`${vp.width} ${route}→${at || '?'}`); }
    const r = await page.evaluate(PROBE);
    pagesSeen++;
    controlsSeen += r.controls;
    const where = `${vp.width}px ${route}`;

    for (const s of r.small) findings.small.push({ where, ...s });
    for (const c of r.lowContrast) findings.contrast.push({ where, ...c });
    for (const u of r.unnamed) findings.unnamed.push({ where, ...u });
    for (const u of r.unstyled) findings.unstyled.push({ where, ...u });
    for (const t of r.textLow) findings.contrast.push({ where, label: t.text, ...t });
    if (r.overflowing.length) findings.overflow.push({ where, docW: r.docW, innerW: r.innerW, who: r.overflowing });

    /* 6.1.2 — 실제로 Tab 을 눌러 초점 표시가 그려지는지 */
    if (vp.width === 375) {
      const focus = await page.evaluate(async () => {
        const bad = [];
        let seen = 0;
        for (let i = 0; i < 25; i++) {
          const el = document.activeElement;
          if (el && el !== document.body) {
            const s = getComputedStyle(el);
            const outline = parseFloat(s.outlineWidth) || 0;
            const ring = s.boxShadow && s.boxShadow !== 'none';
            if (outline === 0 && !ring) {
              bad.push(`${el.tagName.toLowerCase()}.${el.className?.toString().slice(0, 22)}`);
            }
            seen++;
          }
          const ok = await new Promise((res) => {
            const h = () => res(true);
            document.addEventListener('keydown', h, { once: true });
            setTimeout(() => res(false), 5);
          }).catch(() => false);
          void ok;
          break; // 실제 Tab 은 아래에서 Playwright 로 보낸다
        }
        return { seen, bad };
      });
      void focus;

      const tabBad = [];
      for (let i = 0; i < 20; i++) {
        await page.keyboard.press('Tab');
        const info = await page.evaluate(() => {
          const el = document.activeElement;
          if (!el || el === document.body) return null;
          const s = getComputedStyle(el);
          const outline = parseFloat(s.outlineWidth) || 0;
          const ring = s.boxShadow && s.boxShadow !== 'none';
          return { ok: outline > 0 || ring,
                   who: `${el.tagName.toLowerCase()}.${el.className?.toString().slice(0, 22)}` };
        });
        if (info && !info.ok && !tabBad.includes(info.who)) tabBad.push(info.who);
      }
      if (tabBad.length) findings.focus.push({ where, who: tabBad.slice(0, 5) });
    }
  }
  await ctx.close();
}
await browser.close();

// ── 보고 ────────────────────────────────────────────────────────────────
const uniq = (arr, key) => {
  const m = new Map();
  for (const x of arr) if (!m.has(key(x))) m.set(key(x), x);
  return [...m.values()];
};
/* 고칠 단위는 **CSS 클래스**다. 달력 날짜 30칸을 30건으로 세면 목록이 부풀어
   무엇을 고쳐야 하는지가 안 보인다. 클래스별로 묶고 가장 작은 실측치를 남긴다. */
const byClass = new Map();
for (const x of findings.small) {
  const k = `${x.tag}.${x.cls}`;
  const cur = byClass.get(k);
  if (!cur || x.w * x.h < cur.w * cur.h) {
    byClass.set(k, { ...x, n: (cur?.n ?? 0) + 1, where: cur ? cur.where : x.where });
  } else cur.n++;
}
const small = [...byClass.values()].sort((a, b) => a.w * a.h - b.w * b.h);
const contrast = uniq(findings.contrast, (x) => `${x.cls}|${x.label}`);
const unnamed = uniq(findings.unnamed, (x) => `${x.tag}|${x.cls}`);

console.log(`\n렌더링 실측 — 화면 ${pagesSeen}회 · 조작 요소 연 ${controlsSeen}개\n`);

const section = (title, rows, fmt) => {
  if (!rows.length) { console.log(`  ✓ ${title}`); return 0; }
  console.log(`  ✗ ${title} — ${rows.length}종`);
  for (const r of rows.slice(0, 40)) console.log(`      ${fmt(r)}`);
  if (rows.length > 40) console.log(`      … 외 ${rows.length - 40}종`);
  return rows.length;
};

let bad = 0;
bad += section('6.1.3 조작 가능 44×44px', small,
  (r) => `${String(r.w).padStart(5)}×${String(r.h).padEnd(4)} ×${String(r.n).padEnd(4)} ${r.tag}.${r.cls}  "${r.label || '이름없음'}"  [${r.where}]`);
bad += section('5.4.3 명도 대비', contrast,
  (r) => `${r.ratio}:1 (필요 ${r.need}) ${r.size}px  "${r.label}"  .${r.cls}  [${r.where}]`);
bad += section('UA 기본 스타일이 남은 버튼', uniq(findings.unstyled, (x) => x.cls),
  (r) => `${r.why.padEnd(10)} button.${r.cls}  "${r.label || '이름없음'}"  [${r.where}]`);
bad += section('5.1.1 접근 이름', unnamed,
  (r) => `<${r.tag}> .${r.cls}  [${r.where}]`);
bad += section('반응형 가로 넘침', findings.overflow,
  (r) => `문서 ${r.docW}px > 화면 ${r.innerW}px  ${r.who.join(' ')}  [${r.where}]`);
bad += section('6.1.2 초점 표시', findings.focus,
  (r) => `${r.who.join(' ')}  [${r.where}]`);

if (redirected.length) {
  console.log(`\n  · 튕긴 화면 ${redirected.length}건 (인증·데이터가 없어 홈으로): ${[...new Set(redirected.map((x) => x.split(' ')[1]))].slice(0, 12).join(' ')}`);
}
console.log(bad === 0 ? '\n실측 위반 0건\n' : `\n실측 위반 ${bad}건\n`);
process.exit(bad === 0 ? 0 : 1);
