/**
 * 엔진이 서버 없이 실제로 답하는지 본다.
 *
 * 화면을 다 만들고 나서 "그런데 분류가 안 되네"를 알게 되면 그때는 고칠 곳이 화면인지
 * 표인지 모른다. 표만 따로 두드려 본다.
 */
import { classify } from '../src/engine/classify';
import { analyze } from '../src/engine/report';
import { MID_CATEGORIES, byName, byBiz, FORMS } from '../src/engine/tables';
import type { Row } from '../src/engine/store';

const NAMES = [
  '스타벅스 상암DMC점', '컴포즈커피 상암점', '메가엠지씨커피', 'CU상암중앙점',
  'GS25 봉천점', '배달의민족', '쿠팡', '올리브영 신촌점', '리안헤어 상암점',
  '카카오택시-서울33바2592', '서울특별시버스조합', '스타벅스코리아',
  '토스트커피하우스 센트레', '고속철도(KTX)서울-포항', '넷플릭스서비시스코리아 유한회사',
  '(주)우아한형제들', '나이스페이먼츠', '그랜드하얏트서울', '이마트 은평점',
  '메디큐브의원', '알수없는가게이름12345',
];

console.log(`표: 표기 ${FORMS.length} · 사전 이름 ${Object.keys(byName).length} · 사전 번호 ${Object.keys(byBiz).length}`);
console.log(`고를 수 있는 중분류 ${MID_CATEGORIES.length}: ${MID_CATEGORIES.join(' ')}\n`);

let hit = 0;
for (const n of NAMES) {
  const v = classify(n, undefined, undefined, {});
  if (v.category2 !== '카테고리없음') hit++;
  console.log(
    `${v.category2 === '카테고리없음' ? '  ' : '✔ '}${n.padEnd(30)} ${v.category2.padEnd(10)} ` +
    `${(v.category3 ?? '-').padEnd(8)} ${(v.brand ?? '-').padEnd(10)} ${v.source}${v.paymentAgency ? ' [PG]' : ''}`,
  );
}
console.log(`\n분류율 ${hit}/${NAMES.length} (${Math.round((hit / NAMES.length) * 100)}%)\n`);

/* ── 낭비 판정 — 평소를 만들어 두고 튀는 것을 넣는다 ── */
const rows: Row[] = [];
let id = 0;
const push = (date: string, merchant: string, amount: number) =>
  rows.push({ id: String(++id), date, merchant, amount, biz: '', industry: '' });

for (let d = 1; d <= 28; d++) {
  const day = `2026-08-${String(d).padStart(2, '0')}`;
  push(day, '컴포즈커피 상암점', 2500);          // 평소 커피
  push(day, '김밥천국 상암점', 8000);            // 평소 점심
  if (d % 7 === 0) push(day, '서울특별시버스조합', 1500);
}
push('2026-08-15', '스타벅스 상암DMC점', 48000);  // 커피에 48,000 — 튀어야 한다
push('2026-08-20', '리안헤어 상암점', 180000);    // 미용 한 번에 18만
push('2026-08-22', '올리브영 신촌점', 3000);      // 작은 화장품 — 안 튀어야 한다

const r = analyze(rows);
const waste = r.entries.filter((e) => e.waste);
console.log(`소비 ${r.entries.length}건 · 모르는 칸 ${r.unknown}건 · 낭비 ${waste.length}건`);
for (const w of waste) console.log(`  ${w.date} ${w.merchant} ${w.amount} — ${w.reason}`);
const quiet = r.entries.filter((e) => !e.waste && e.amount >= 100000);
if (quiet.length) console.log('놓친 큰 소비:', quiet.map((e) => `${e.merchant} ${e.amount}`).join(', '));
