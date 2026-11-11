const {chromium}=require('playwright');const assert=require('node:assert/strict');
(async()=>{const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true});try{
const page=await browser.newPage({viewport:{width:1440,height:1000},baseURL:process.env.BOOKING_URL||'http://127.0.0.1:8080'});const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto(process.env.BOOKING_URL||'http://127.0.0.1:8080');
async function login(name,pass){await page.locator('#username').fill(name);await page.locator('#password').fill(pass);await page.locator('#auth-form [type=submit]').click();await page.locator('#workspace').waitFor({state:'visible'});await page.waitForFunction(()=>document.querySelector('#equipment-list .card'));}
await login('alice','password123');
// A response began under Alice; complete it only after Bob has logged in.
let release,started;const seen=new Promise(r=>started=r),gate=new Promise(r=>release=r);let held=false;
await page.route('**/api/notifications?*',async route=>{if(held)return route.continue();held=true;started();await gate;await route.fulfill({json:[{id:999999,reservation_id:1,message:'PRIVATE_ALICE_NOTIFICATION',created_at:'2026-11-01T00:00:00Z',read_at:null}]});});
await page.locator('#refresh').click();await seen;await page.locator('#identity button').click();await page.locator('#auth-panel').waitFor({state:'visible'});
await login('admin','admin12345');release();await page.waitForTimeout(250);
assert.doesNotMatch(await page.locator('#notifications').innerText(),/PRIVATE_ALICE_NOTIFICATION/);
await page.reload();await page.locator('#workspace').waitFor({state:'visible'});assert.match(await page.locator('#identity').innerText(),/admin/);assert.deepEqual(errors,[]);
const fs=require('node:fs');
await page.locator('#identity button').click();
const flow=eval('('+fs.readFileSync(require('node:path').join(__dirname,'booking_browser_check.js'),'utf8').trim()+')');
console.log(JSON.stringify(await flow(page,{mobile:false})));
// A write succeeds on the server, but its HTTP response is lost in transit.
const suffix=Date.now().toString(36);
const admin=await (await page.request.post('/api/auth/login',{data:{username:'admin',password:'admin12345'}})).json();
const resource=await (await page.request.post('/api/equipment',{headers:{Authorization:'Bearer '+admin.token},data:{name:'desktop-'+suffix,category:'模拟测试',location:'模拟实验室',description:'<img src=x onerror=alert(1)>',approvalRequired:false,trainingRequired:false}})).json();
const user=await (await page.request.post('/api/auth/register',{data:{username:'desk_'+suffix,password:'Synthetic123!'}})).json();
await page.evaluate(token=>sessionStorage.setItem('bookingToken',token),user.token);await page.reload();await page.locator('#workspace').waitFor({state:'visible'});
await page.locator('#search').fill(resource.name);await page.locator('#search-form').evaluate(form=>form.requestSubmit());
const card=page.locator('#equipment-list .card').filter({hasText:resource.name});await card.waitFor();assert.equal(await card.locator('img').count(),0);
await card.getByRole('button',{name:'选择时段'}).click();const day=new Date(Date.now()+3*86400000).toISOString().slice(0,10);
await page.locator('#start-at').fill(day+'T10:00');await page.locator('#end-at').fill(day+'T11:00');await page.locator('#purpose').fill('desktop lost response');
const keys=[];let dropped=false;await page.route('**/api/reservations',async route=>{if(route.request().method()!=='POST')return route.continue();keys.push(route.request().postDataJSON().requestKey);if(dropped)return route.continue();dropped=true;await route.fetch();await route.abort('failed');});
await page.locator('#booking-form [type=submit]').click();await page.locator('#toast').waitFor({state:'visible'});await page.waitForFunction(()=>!document.querySelector('#booking-form [type=submit]').disabled);
await page.locator('#booking-form [type=submit]').click();await page.locator('#booking-dialog').waitFor({state:'hidden'});
assert.equal(keys.length,2);assert.equal(keys[0],keys[1]);const rows=await (await page.request.get('/api/reservations',{headers:{Authorization:'Bearer '+user.token}})).json();assert.equal(rows.length,1);
await page.request.post('/api/reservations/'+rows[0].id+'/cancel',{headers:{Authorization:'Bearer '+user.token}});
await page.request.put('/api/equipment/'+resource.id+'/enabled',{headers:{Authorization:'Bearer '+admin.token},data:{enabled:false,expectedVersion:resource.version}});
console.log('PASS desktop 1440px: late prior-user HTTP isolation, refresh restores session, no JS errors');
}finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1});
