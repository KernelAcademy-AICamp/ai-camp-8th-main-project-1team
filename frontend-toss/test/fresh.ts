/** 처음 쓰는 사람 — 이번 달 한 건뿐일 때 전월 대비 줄이 뜨면 안 된다. */
import { analyze } from '../src/engine/report';
import type { Row } from '../src/engine/store';
const one: Row[] = [{ id: 'a', date: '2026-08-29', time: '19:10', merchant: '스타벅스 상암DMC점', amount: 4500, biz: '', industry: '' }];
const r = analyze(one);
const months: Record<string, number> = {};
for (const e of r.entries) months[e.date.slice(0, 7)] = (months[e.date.slice(0, 7)] ?? 0) + e.amount;
const now = new Date();
const key = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1);
console.log('monthlySpend 키:', Object.keys(months));
console.log('지난 달 값:', months[key(prev)] ?? '(없음)');
console.log('전월 대비 줄:', months[key(prev)] == null ? '안 뜬다 ✔' : '뜬다 ✗');
