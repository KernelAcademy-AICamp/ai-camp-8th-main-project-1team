import { analyze } from '../src/engine/report';
import { seedRows } from '../src/demoData';
const r = analyze(seedRows());
const w = r.entries.filter((e) => e.waste).sort((a, b) => b.amount - a.amount);
console.log(`전체 ${r.entries.length}건 · 모르는 칸 ${r.unknown} · 낭비 ${w.length}건 (${Math.round(w.length/r.entries.length*100)}%)`);
for (const x of w.slice(0, 12)) console.log(`  ${x.date} ${x.time ?? '  -  '} ${String(x.amount).padStart(7)} ${x.category2.padEnd(8)} ${x.merchant.padEnd(24)} ${x.reason}`);
const cats: Record<string,[number,number]> = {};
for (const e of r.entries) { const c = cats[e.category2] ??= [0,0]; c[0]++; if (e.waste) c[1]++; }
console.log('\n카테고리별');
for (const [c,[n,k]] of Object.entries(cats).sort((a,b)=>b[1][0]-a[1][0])) console.log(`  ${c.padEnd(10)} ${String(k).padStart(3)}/${String(n).padStart(3)}`);
