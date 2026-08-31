/** 화면이 부르는 경로가 <b>하나도 빠짐없이</b> 404 가 아닌지 — 라우터만 두드린다. */
import { serve } from '../src/lib/localServer';
import { ApiError } from '../src/lib/api';

const CALLS: [string, string, unknown?][] = [
  ['GET', '/api/users/1'],
  ['GET', '/api/categories'],
  ['GET', '/api/mydata/payments?userId=1&months=12'],
  ['GET', '/api/mydata/merchant/1018302925'],
  ['POST', '/api/mydata/sync?userId=1'],
  ['GET', '/api/report/monthly?userId=1'],
  ['GET', '/api/report/period?userId=1&period=week&offset=0'],
  ['GET', '/api/report/period?userId=1&period=month&offset=0'],
  ['GET', '/api/report/peer?userId=1&days=7'],
  ['GET', '/api/ml/waste/1'],
  ['GET', '/api/alert/list?userId=1'],
  ['POST', '/api/alert/rescan?userId=1'],
  ['GET', '/api/merchant-category/unclassified?userId=1'],
  ['GET', '/api/merchant-category/preview?name=%EC%8A%A4%ED%83%80%EB%B2%85%EC%8A%A4'],
  ['GET', '/api/guardian/home?userId=1'],
  ['POST', '/api/guardian/sync?userId=1'],
  ['GET', '/api/guardian/report/weekly?userId=1&weeksAgo=0'],
  ['POST', '/api/analytics/track', { event: 'x' }],
  ['POST', '/api/consumption', { userId: 1, categoryCode: '카페/간식', amount: 4500, occurredAt: '2026-08-29T19:40:00', merchantName: '스타벅스 상암DMC점' }],
];

let bad = 0;
for (const [m, path, body] of CALLS) {
  try {
    await serve(m, path, body);
    console.log(`  ok    ${m} ${path.split('?')[0]}`);
  } catch (e) {
    bad++;
    const s = e instanceof ApiError ? e.status : '?';
    console.log(`  ${s}   ${m} ${path.split('?')[0]}  ← ${(e as Error).message}`);
  }
}
console.log(bad === 0 ? '\n모든 경로가 답한다.' : `\n${bad}개가 답하지 않는다.`);
