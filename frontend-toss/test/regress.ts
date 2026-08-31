/**
 * 신고된 증상과 <b>같은 종류</b>가 남아 있는지 훑는다.
 *
 * 증상: 소비를 넣어도 되묻기 창이 "답할 소비가 없어요" 로 떴다.
 * 원인: 되묻기 목록이 `카테고리없음` 을 걸렀다 — 사전에 없는 가게를 적으면 통째로 빠졌다.
 *
 * 같은 잘못("모르면 없는 셈 친다")이 어디에 또 있는지 값으로 확인한다.
 */
/* Node 에는 `localStorage` 가 없다. 저장소만 흉내 내고 나머지는 실제 코드를 그대로 돌린다. */
const mem = new Map<string, string>();
(globalThis as unknown as { localStorage: Storage }).localStorage = {
  getItem: (k: string) => mem.get(k) ?? null,
  setItem: (k: string, v: string) => { mem.set(k, v); },
  removeItem: (k: string) => { mem.delete(k); },
  clear: () => mem.clear(),
  key: (i: number) => [...mem.keys()][i] ?? null,
  get length() { return mem.size; },
} as Storage;

import { serve } from '../src/lib/localServer';
import { classify } from '../src/engine/classify';

/** 실제 사람이 적을 법한 이름 — 절반쯤은 사전에 없다. */
const NAMES = [
  '스타벅스 상암DMC점', '컴포즈커피', 'CU상암중앙점', '배달의민족', '쿠팡',
  '동네노점상', '그날그집 골목가게', '엄마손칼국수', '형제상회', '우리분식',
  '나이스페이먼츠', '서울특별시버스조합', '중앙시장 과일가게', '이름없는카페',
  '롯데리아 신촌점', '올리브영', '메가엠지씨커피', '뚜레쥬르 봉천점',
  '학교앞문구점', '주차장정산기',
];

const day = new Date();
const iso = `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`;

let unknown = 0, missing = 0;
console.log('가게마다 — 분류 · 넣기 · 되묻기 목록에 나오는가\n');
for (let i = 0; i < NAMES.length; i++) {
  const name = NAMES[i];
  const v = classify(name, undefined, undefined, {});
  const known = v.category2 !== '카테고리없음';
  if (!known) unknown++;

  const r = await serve<{ paymentId: string; flagged: boolean; category2: string | null }>(
    'POST', '/api/consumption',
    { userId: 1, categoryCode: v.category2, amount: 3000 + i * 700,
      occurredAt: `${iso}T${String(9 + (i % 12)).padStart(2, '0')}:${String((i * 5) % 60).padStart(2, '0')}:00`,
      merchantName: name });

  const pending = await serve<{ paymentId: string }[]>('GET', '/api/ml/pending');
  const found = pending.some((p) => p.paymentId === r.paymentId);
  if (!found) missing++;
  console.log(`  ${found ? '✔' : '✗'} ${(known ? v.category2 : '(모름)').padEnd(8)} ${name}`);
}

console.log(`\n사전에 없는 가게 ${unknown}/${NAMES.length} · 되묻기에서 빠진 것 ${missing}건`);

/* ── 합계가 모르는 칸을 세는가 ── */
const rep = await serve<{ totalSpend: number; monthlySpend: Record<string, number> }>(
  'GET', '/api/report/monthly?userId=1');
const home = await serve<{ challenge: { securedSaving: number } }>('GET', '/api/guardian/home?userId=1');
const expect = NAMES.reduce((s, _, i) => s + 3000 + i * 700, 0);
console.log(`\n합계 확인 (넣은 돈 ${expect.toLocaleString('ko-KR')}원)`);
console.log(`  리포트 전체 지출  ${rep.totalSpend.toLocaleString('ko-KR')}원  ${rep.totalSpend === expect ? '✔' : '✗ 어긋남'}`);
console.log(`  홈 이번 달 쓴 돈  ${home.challenge.securedSaving.toLocaleString('ko-KR')}원  ${home.challenge.securedSaving === expect ? '✔' : '✗ 어긋남'}`);
console.log(`  달별 합           ${Object.values(rep.monthlySpend).reduce((a, b) => a + b, 0).toLocaleString('ko-KR')}원`);

/* ── 모르는 칸에 '새는 돈' 을 고르면 세는가 ── */
const pending = await serve<{ paymentId: string; category2: string }[]>('GET', '/api/ml/pending');
const target = pending.find((p) => p.category2 === '카테고리없음');
if (!target) console.log('\n모르는 칸이 되묻기 목록에 없다 ✗');
else {
  await serve('POST', '/api/ml/verdict', { paymentId: target.paymentId, waste: true });
  const waste = await serve<{ paymentId: string }[]>('GET', '/api/ml/waste/1');
  console.log(`\n모르는 칸에 '새는 돈' 고른 뒤`);
  console.log(`  낭비 목록에 들어갔나  ${waste.some((w) => w.paymentId === target.paymentId) ? '✔' : '✗ 답이 버려진다'}`);
  const after = await serve<{ paymentId: string }[]>('GET', '/api/ml/pending');
  console.log(`  되묻기에서 빠졌나    ${after.some((p) => p.paymentId === target.paymentId) ? '✗ 또 묻는다' : '✔'}`);
}
