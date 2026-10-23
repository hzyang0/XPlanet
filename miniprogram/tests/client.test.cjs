const {test,beforeEach}=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
let storage,transport,redirects;
global.wx={getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k),request:o=>transport(o),reLaunch:o=>redirects.push(o.url)};
const api=require('../utils/api'),time=require('../utils/time');
beforeEach(()=>{storage=new Map();redirects=[];transport=()=>{};});
test('stable key for exact retry, separate keys for changed body and identity',()=>{
 storage.set('session',{token:'a'}); const body={id:1,startAt:'2026-11-01T02:00:00Z'};
 const first=api.keyFor(body);assert.equal(api.keyFor({...body}),first);
 assert.notEqual(api.keyFor({...body,id:2}),first);
 storage.set('session',{token:'b'});assert.notEqual(api.keyFor(body),first);
});
test('401 clears credentials and redirects without automatic mutation retry',async()=>{
 storage.set('session',{token:'a'});storage.set('draft',{});let calls=0;
 transport=o=>{calls++;assert.equal(o.header.Authorization,'Bearer a');o.success({statusCode:401,data:{message:'过期'}});};
 await assert.rejects(api.request('/reservations','POST',{}),/过期/);
 assert.equal(calls,1);assert.equal(api.session(),undefined);assert.equal(storage.has('draft'),false);assert.equal(redirects.length,1);
});
test('late response from previous identity cannot appear in new account',async()=>{
 storage.set('session',{token:'a'});let response;transport=o=>{response=o;};
 const pending=api.request('/reservations');storage.set('session',{token:'b'});
 response.success({statusCode:200,data:[{private:'a'}]});await assert.rejects(pending,/会话已切换/);
});
test('changing server removes token; malformed endpoints are rejected',()=>{
 storage.set('session',{token:'secret'});api.configure('https://booking.example.com/');
 assert.equal(api.session(),undefined);assert.equal(api.base(),'https://booking.example.com');
 assert.throws(()=>api.configure('https://example.com/api'),/服务地址/);
});
test('local picker values round-trip to UTC; invalid dates and unaligned slots rejected',()=>{
 const utc=time.iso('2026-11-01','09:30');const d=new Date(utc);
 assert.equal(time.date(d),'2026-11-01');assert.equal(time.time(d),'09:30');
 assert.throws(()=>time.iso('2026-02-30','09:30'),/无效/);
 assert.throws(()=>time.iso('2026-11-01','09:15'),/半点/);
});
test('network ambiguity retains stable retry key',async()=>{
 const key=api.keyFor({purpose:'test'});transport=o=>o.fail();
 await assert.rejects(api.request('/reservations','POST',{}),/可能已生效/);
 assert.equal(api.keyFor({purpose:'test'}),key);
});
test('every registered page loads as a controller and has a view',()=>{
 const fs=require('node:fs');const config=require('../app.json');global.Page=page=>assert.equal(typeof page.data,'object');
 for(const page of config.pages){assert.ok(fs.existsSync(path.join(__dirname,'..',page+'.wxml')));require('../'+page+'.js');}
 delete global.Page;
});
