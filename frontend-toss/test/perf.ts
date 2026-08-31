/** 소비가 쌓였을 때도 화면이 2초 안에 뜨는가 — 검수 기준이다. */
import { analyze } from '../src/engine/report';
import { seedRows } from '../src/demoData';
import type { Row } from '../src/engine/store';

const seed = seedRows();
for (const n of [250, 1000, 3000, 10000]) {
  const rows: Row[] = [];
  for (let i = 0; i < n; i++) {
    const s = seed[i % seed.length];
    rows.push({ ...s, id: `r${i}` });
  }
  const t0 = performance.now();
  const r = analyze(rows);
  const ms = performance.now() - t0;
  console.log(`${String(n).padStart(6)}건  ${ms.toFixed(0).padStart(6)}ms   낭비 ${r.entries.filter(e=>e.waste).length}`);
}
