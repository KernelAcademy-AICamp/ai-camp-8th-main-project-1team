/** 기기 안 서버가 화면이 부르는 경로마다 제대로 답하는지 — 화면 없이 두드린다. */
import { analyze } from '../src/engine/report';
import { seedRows } from '../src/demoData';
import { guardianHome, weeklyReport, periodSpend } from '../src/lib/localGuardian';

const es = analyze(seedRows()).entries;
try {
  const h = guardianHome(es);
  console.log('guardian/home  ok  쓴 돈', h.challenge.securedSaving, '· 눈에 띈',
    h.challenge.challengeCap - h.challenge.remainingCap, '· 카테고리', h.challenge.categorySpend.length);
} catch (e) { console.log('guardian/home  실패:', (e as Error).message); }
try {
  const w = weeklyReport(es, 0);
  console.log('report/weekly  ok  요일', w.days.length, '· 주별', w.trend.length, '·', w.headline);
} catch (e) { console.log('report/weekly  실패:', (e as Error).message); }
for (const p of ['week', 'month'] as const) {
  try {
    const s = periodSpend(es, p, 0);
    console.log(`report/period ${p}  ok  ${s.start}~${s.end} 합계 ${s.total} 건수 ${s.count} 눈에띔 ${s.flagged}`);
  } catch (e) { console.log(`report/period ${p}  실패:`, (e as Error).message); }
}
