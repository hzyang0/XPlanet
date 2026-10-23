// Opt-in synthetic-user flow against a demo server. Never point at production.
const assert=require('node:assert/strict');
if(process.env.RUN_MINI_LIVE!=='true')throw new Error('Set RUN_MINI_LIVE=true for a disposable demo server');
const server=process.env.BOOKING_URL||'http://127.0.0.1:8080';
const storage=new Map([['server',server]]);
global.wx={getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k),reLaunch:()=>{},request:async o=>{try{const r=await fetch(o.url,{method:o.method,headers:o.header,body:o.method==='GET'?undefined:JSON.stringify(o.data||{})});o.success({statusCode:r.status,data:await r.json()});}catch(e){o.fail(e);}}};
const api=require('../utils/api');
(async()=>{
 const suffix=Date.now().toString(36);const password='Synthetic123!';
 const admin=await api.request('/auth/login','POST',{username:'admin',password:process.env.BOOKING_ADMIN_PASSWORD||'admin12345'});
 storage.set('session',admin);
 const equipment=await api.request('/equipment','POST',{name:'mini-test-'+suffix,category:'模拟设备',location:'模拟实验室',description:'自动化模拟用户流程，不用于真实实验',approvalRequired:false,trainingRequired:false});
 storage.delete('session');const a=await api.request('/auth/register','POST',{username:'mini_a_'+suffix,password});
 const b=await api.request('/auth/register','POST',{username:'mini_b_'+suffix,password});
 const start=new Date(Math.ceil((Date.now()+86400000)/1800000)*1800000);
 const body={equipmentId:equipment.id,startAt:start.toISOString(),endAt:new Date(+start+3600000).toISOString(),purpose:'模拟预约测试'};
 storage.set('session',a);const key=api.keyFor(body);const first=await api.request('/reservations','POST',{...body,requestKey:key});
 assert.equal(first.status,'CONFIRMED');assert.equal((await api.request('/reservations','POST',{...body,requestKey:api.keyFor(body)})).id,first.id);
 storage.set('session',b);const second=await api.request('/reservations','POST',{...body,requestKey:api.keyFor(body)});assert.equal(second.status,'WAITLISTED');
 await assert.rejects(api.request('/reservations/'+first.id+'/cancel','POST'));
 storage.set('session',a);await api.request('/reservations/'+first.id+'/cancel','POST');
 storage.set('session',b);assert.equal((await api.request('/reservations/'+second.id)).status,'OFFERED');
 assert.equal((await api.request('/reservations/'+second.id+'/accept','POST')).status,'CONFIRMED');
 const moved={startAt:new Date(+start+7200000).toISOString(),endAt:new Date(+start+10800000).toISOString()};
 const changed=await api.request('/reservations/'+second.id+'/reschedule','POST',{...moved,requestKey:api.keyFor(moved)});assert.equal(changed.status,'CONFIRMED');
 await api.request('/reservations/'+changed.id+'/cancel','POST');
 storage.set('session',admin);await api.request('/equipment/'+equipment.id+'/enabled','PUT',{enabled:false,expectedVersion:equipment.version});
 console.log('PASS: simulated users, authorization, stable retry, conflict, waitlist offer, accept, reschedule, cancellation; synthetic equipment disabled.');
})().catch(e=>{console.error(e);process.exitCode=1;});
