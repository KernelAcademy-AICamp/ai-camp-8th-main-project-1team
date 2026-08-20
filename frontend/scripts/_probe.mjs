import { chromium } from 'playwright';
const BASE=process.env.BASE ?? 'http://localhost:4180';
const route=process.argv[2], sel=process.argv.slice(3);
const b=await chromium.launch(); const ctx=await b.newContext({viewport:{width:390,height:844}});
if(process.env.LINKED!=='0') await ctx.addInitScript(([u,t])=>{localStorage.setItem('mydata_onboarded','true');localStorage.setItem('demo_user_id',u);if(t)localStorage.setItem('auth_token',t);},[process.env.USER_ID??'1',process.env.AUTH_TOKEN??'']);
const p=await ctx.newPage(); p.on('pageerror',e=>console.log('  pageerror:',e.message));
await p.goto(`${BASE}/#/${route}`,{waitUntil:'domcontentloaded'}); await p.waitForTimeout(1200);
for(const s of sel){
  const r=await p.evaluate((s)=>{const el=document.querySelector(s); if(!el) return null;
    const b=el.getBoundingClientRect(); const c=getComputedStyle(el);
    return {x:Math.round(b.x),y:Math.round(b.y),w:Math.round(b.width),h:Math.round(b.height),
      pos:c.position,disp:c.display,minH:c.minHeight,ovf:c.overflow};},s);
  console.log(`  ${s.padEnd(26)}`, r ? JSON.stringify(r) : '없음');
}
await b.close();
