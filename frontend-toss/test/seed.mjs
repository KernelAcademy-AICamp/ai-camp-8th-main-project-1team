import { chromium } from 'playwright';
import { writeFileSync } from 'fs';
const b = await chromium.launch(); const p = await b.newPage();
await p.goto('http://localhost:8899/demo.html#/home'); await p.waitForTimeout(2500);
const rows = await p.evaluate(() => localStorage.getItem('moa.rows.v1'));
writeFileSync('test/seed.json', rows ?? '[]');
console.log(`씨앗 ${JSON.parse(rows ?? '[]').length}건 저장`);
await b.close();
