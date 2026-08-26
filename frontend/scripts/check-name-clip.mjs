/**
 * **가맹점 이름이 화면에서 잘리는지** 실제 브라우저로 재는 검사.
 *
 * <b>왜 코드만 읽어서는 못 잡나.</b> 잘림은 글자 수가 아니라 **그려진 폭**이 정한다 —
 * 같은 12자라도 `CJ더마켓 CJ제일제당`(라틴 섞임)과 `한국철도인재개발원`(한글)의 폭이 다르고,
 * 옆에 붙는 카테고리 배지·카드 이름·금액이 남는 자리를 바꾼다. 실제로 CSS 에 말줄임이
 * 걸려 있는데도 화면이 길었다 — 이름과 배지가 한 상자에 있어 **배지가 밀려났기** 때문이고,
 * 그건 렌더해 봐야 보인다(2026-08-26).
 *
 * 재는 것:
 *   1. 이름이 잘리는가        — 가로로 넘치거나(`scrollWidth`), 두 줄을 넘겨 접히거나(`scrollHeight`)
 *   2. 배지가 살아 있는가      — 카테고리 배지는 눌러서 고치는 컨트롤이다. 잘리면 기능이 없어진다
 *   3. 줄이 가로로 넘치는가    — 목록 전체가 옆으로 밀리면 안 된다
 *
 * 값은 **운영의 실제 표시명 중 가장 긴 것들**이다(scripts 밖 fixture 로 준다).
 *
 * 서버가 떠 있어야 한다:  npm run dev
 *   node scripts/check-name-clip.mjs
 */
import { chromium } from 'playwright';

const BASE = (process.env.BASE ?? 'http://localhost:5173').replace(/\/$/, '');
/**
 * **운영에서 실제로 가장 긴 표시명들**(2026-08-26). 값을 저장소 안에 둬야 누구나 돌릴 수 있다.
 *
 * 여기 있는 것은 <b>가맹점명</b>이지 사람의 정보가 아니다 — 상호는 카드 명세서의 공개 표기이고,
 * 사용자·금액·일시는 담지 않는다.
 */
const NAMES = [
  [
    "Vatfree FRVA Netherland",
    "RESIDUE"
  ],
  [
    "ALP*shanghaishihuangpu",
    "RAW"
  ],
  [
    "LinkedInPreA *45461616",
    "RAW"
  ],
  [
    "ALP*shanghaidiandoude",
    "RAW"
  ],
  [
    "ALP*shanghaishihuangp",
    "RAW"
  ],
  [
    "사우 커피바 SAU Coffee bar",
    "RAW"
  ],
  [
    "DPP Tramv*PH0s002211",
    "RAW"
  ],
  [
    "Basics Coffee Pallad",
    "RESIDUE"
  ],
  [
    "ALP*PersonalServices",
    "RAW"
  ],
  [
    "BREAD&CO HLAVNI NAD",
    "RESIDUE"
  ],
  [
    "프렌즈 야드 Friends&Yard",
    "RESIDUE"
  ],
  [
    "ALP*Shanghai Disney",
    "RAW"
  ],
  [
    "ALP*SH Transit Card",
    "RAW"
  ],
  [
    "NAVAJO INVESTMENT",
    "RAW"
  ],
  [
    "마포애경타운 새틀라이트문구外",
    "RESIDUE"
  ],
  [
    "SLICE BABY SRO",
    "RESIDUE"
  ],
  [
    "아람스제이 요리하는 남자",
    "RESIDUE"
  ],
  [
    "화인피부과 비뇨기과 의원",
    "RESIDUE"
  ],
  [
    "미니말레 커피뢰스터 과천",
    "RESIDUE"
  ],
  [
    "LOKAL COFFEE",
    "RESIDUE"
  ],
  [
    "빈스미스 커피 로스터스",
    "RESIDUE"
  ],
  [
    "신세계 본점 에키노마에",
    "RESIDUE"
  ],
  [
    "CJ더마켓 CJ제일제당",
    "RESIDUE"
  ],
  [
    "공제제외 KCT티플러스",
    "RESIDUE"
  ],
  [
    "토스페이먼츠",
    "AGENCY_ONLY"
  ],
  [
    "고척아이파크쇼핑센터",
    "RESIDUE"
  ],
  [
    "세븐틴코인노래연습장",
    "RAW"
  ]
];
const rows = NAMES.map(([name, source], i) => ({
  paymentId: `p${i}`, date: '2026-08-19T12:00:00', category: '식비', category2: '식비',
  category2Llm: null, amount: 12345, merchantName: `${name} 원문꼬리`,
  cardName: 'KB국민 My WE:SH', cardColor: '#FFB800', companyName: 'KB국민카드',
  businessNumber: '0000000011', brand: null, displayName: name,
  displayNameSource: source, viaAgency: null,
}));

const SEED = () => {
  localStorage.setItem('mydata_onboarded', 'true');
  localStorage.setItem('demo_user_id', '1');
  localStorage.setItem('auth_token', 'check-name-clip');
};

async function stub(ctx) {
  await ctx.route('**/api/**', async (route) => {
    const url = route.request().url();
    if (url.includes('/api/mydata/payments')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rows) });
    }
    if (url.includes('/api/users/')) {
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ id: 1, nickname: '검증-tester' }) });
    }
    return route.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"검사용 스텁"}' });
  });
}

const browser = await chromium.launch();
let bad = 0;
for (const width of [360, 390, 430]) {
  const ctx = await browser.newContext({ viewport: { width, height: 900 } });
  await ctx.addInitScript(SEED);
  await stub(ctx);
  const page = await ctx.newPage();
  await page.goto(`${BASE}/#/transactions`, { waitUntil: 'networkidle' });
  await page.waitForSelector('.sp-card .list-item .tx b .nm', { timeout: 8000 }).catch(() => {});

  const found = await page.$$eval('.sp-card .list-item', (items) => items.map((it) => {
    const nm = it.querySelector('.tx b .nm');
    const tag = it.querySelector('.tx b .sp-tag');
    const row = it.getBoundingClientRect();
    return {
      name: nm ? nm.textContent.trim() : null,
      // 두 줄까지는 접어서 다 보여 준다 — 그 안에 들어가면 잘린 것이 아니다.
      clipped: nm ? (nm.scrollWidth > nm.clientWidth + 1 || nm.scrollHeight > nm.clientHeight + 1) : false,
      lines: nm ? Math.round(nm.scrollHeight / parseFloat(getComputedStyle(nm).lineHeight)) : 0,
      tagVisible: tag ? tag.getBoundingClientRect().right <= row.right + 1 : null,
      rowRight: row.right,
    };
  }));
  if (found.length === 0) {
    console.log(`  ${width}px — 줄을 못 찾았다. 확인 못 한 것은 통과가 아니다.`);
    bad++;
    await ctx.close();
    continue;
  }
  const overflow = await page.evaluate(() =>
    document.documentElement.scrollWidth > document.documentElement.clientWidth + 1);

  const clipped = found.filter((f) => f.clipped);
  const hiddenTag = found.filter((f) => f.tagVisible === false);
  console.log(`\n[${width}px] 줄 ${found.length} · 잘린 이름 ${clipped.length} · 밀려난 배지 ${hiddenTag.length}`
    + ` · 가로 넘침 ${overflow ? '있다' : '없다'}`);
  for (const f of clipped) console.log(`   잘림  ${f.name}`);
  const wrapped = found.filter((f) => f.lines > 1 && !f.clipped);
  for (const f of wrapped) console.log(`   두 줄  ${f.name}`);
  for (const f of hiddenTag) console.log(`   배지밀림  ${f.name}`);
  if (overflow) bad++;
  if (hiddenTag.length) bad++;
  await ctx.close();
}
await browser.close();
console.log(bad === 0 ? '\n가로 넘침·배지 밀림 없음.' : `\n문제 ${bad}건.`);
process.exit(bad === 0 ? 0 : 1);
