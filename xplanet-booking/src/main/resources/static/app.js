'use strict';
const $=id=>document.getElementById(id);
const state={token:sessionStorage.getItem('bookingToken'),user:null,equipment:[],reservations:[],notifications:[],register:false,selected:null,draft:null};
const labels={WAITLISTED:'候补中',OFFERED:'待确认候补',PENDING_APPROVAL:'待审批',CONFIRMED:'已确认',IN_USE:'使用中',COMPLETED:'已完成',CANCELLED:'已取消',REJECTED:'已拒绝',EXPIRED:'已过期',NO_SHOW:'未签到'};
function el(tag,text,cls){const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;}
function button(text,fn,cls){const b=el('button',text,cls);b.type='button';b.addEventListener('click',()=>run(()=>fn(b)));return b;}
let toastTimer;
function toast(message){$('toast').textContent=message;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,6500);}
async function run(fn){try{await fn();}catch(e){toast(e.message||'请求失败');}}
async function api(path,method='GET',body){
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),12000);
 try{
  const headers={'Content-Type':'application/json'};if(state.token)headers.Authorization='Bearer '+state.token;
  const response=await fetch('/api'+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:controller.signal});
  const data=await response.json();
  if(!response.ok){if(response.status===401&&!path.startsWith('/auth/'))resetAuth();throw new Error(data.message||'请求失败');}return data;
 }catch(e){if(e.name==='AbortError')throw new Error('请求超时。再次提交预约会复用原请求标识，请勿更改内容后盲目重试。');throw e;}
 finally{clearTimeout(timer);}
}
function resetAuth(){state.token=null;state.user=null;state.equipment=[];state.reservations=[];state.notifications=[];state.selected=null;state.draft=null;sessionStorage.removeItem('bookingToken');$('workspace').hidden=true;$('auth-panel').hidden=false;for(const id of ['identity','equipment-list','reservation-list','notifications','summary','training-user','audit-list'])$(id).replaceChildren();document.querySelectorAll('dialog[open]').forEach(d=>d.close());}
function utcInput(value){return new Date(value).toISOString().slice(0,19);}
function date(value){if(!value)return '—';return new Date(value+(value.endsWith('Z')?'':'Z')).toLocaleString('zh-CN',{hour12:false});}
function inputDate(d){const local=new Date(d.getTime()-d.getTimezoneOffset()*60000);return local.toISOString().slice(0,16);}
function equipmentName(id){return state.equipment.find(e=>e.id===id)?.name||'设备 #'+id;}
async function loadSession(){
 const me=await api('/me');state.user=me.user;state.qualifications=me.qualifications;
 $('auth-panel').hidden=true;$('workspace').hidden=false;$('admin-tab').hidden=state.user.role!=='ADMIN';
 $('identity').replaceChildren(el('span',state.user.username+' · '+(state.user.role==='ADMIN'?'管理员':'用户')+' '),button('退出',async()=>{await api('/auth/logout','POST');resetAuth();}));
 $('reservations-title').textContent=state.user.role==='ADMIN'?'全部预约':'我的预约';await refresh();
 for(const b of $('tabs').querySelectorAll('button')){b.classList.toggle('active',b.dataset.tab==='equipment');$('tab-'+b.dataset.tab).hidden=b.dataset.tab!=='equipment';}
}
async function refresh(){await Promise.all([loadEquipment(),loadReservations(),loadNotifications()]);if(state.user?.role==='ADMIN')await loadAdmin();}
async function loadEquipment(more=false){
 const rows=await api('/equipment?q='+encodeURIComponent($('search').value)+'&limit=50&offset='+(more?state.equipment.length:0));
 state.equipment=more?[...state.equipment,...rows]:rows;$('more-equipment').hidden=rows.length<50;renderEquipment();
}
function renderEquipment(){
 const root=$('equipment-list');root.replaceChildren();
 if(!state.equipment.length)root.append(el('p','没有匹配的设备','empty'));
 for(const e of state.equipment){
  const card=el('article',undefined,'card');card.append(el('div',e.category+' / '+e.location,'category'),el('h3',e.name),el('div',e.description,'description'));
  const tags=el('div',undefined,'tags');tags.append(el('span',e.enabled?'开放预约':'已停用','tag'));
  if(e.approvalRequired)tags.append(el('span','需审批','tag'));if(e.trainingRequired)tags.append(el('span',state.qualifications.includes(e.id)?'已获培训资格':'需培训资格','tag'));card.append(tags);
  const actions=el('div',undefined,'actions'),book=button('选择时段',()=>openBooking(e),'primary');book.disabled=!e.enabled;actions.append(book);
  if(state.user.role==='ADMIN')actions.append(button(e.enabled?'停用设备':'启用设备',async()=>{
   if(e.enabled&&!confirm('停用将取消此设备所有尚未使用的预约和候补。确认继续？'))return;
   await api('/equipment/'+e.id+'/enabled','PUT',{enabled:!e.enabled,expectedVersion:e.version});toast('设备状态已更新');await refresh();
  }));card.append(actions);root.append(card);
 }
}
function openBooking(e){
 state.selected=e;state.draft=null;$('booking-title').textContent='预约 · '+e.name;$('booking-description').textContent=e.location+' · '+(e.approvalRequired?'需要审批':'直接确认');
 const d=new Date();d.setSeconds(0,0);d.setMinutes(Math.ceil((d.getMinutes()+1)/30)*30);
 $('start-at').value=inputDate(d);$('end-at').value=inputDate(new Date(d.getTime()+3600000));$('purpose').value='';$('schedule').replaceChildren();$('booking-dialog').showModal();
}
async function loadReservations(more=false){const rows=await api('/reservations?limit=50&offset='+(more?state.reservations.length:0));state.reservations=more?[...state.reservations,...rows]:rows;$('more-reservations').hidden=rows.length<50;renderReservations();}
function renderReservations(){
 const root=$('reservation-list');root.replaceChildren();if(!state.reservations.length)root.append(el('p','还没有预约，先选择一台设备。','empty'));
 for(const r of state.reservations){
  const card=el('article',undefined,'reservation'),head=el('div',undefined,'section-title');head.append(el('strong','#'+r.id+' · '+equipmentName(r.equipmentId)),el('span',labels[r.status], 'status '+r.status));
  card.append(head,el('div',date(r.startAt)+' → '+date(r.endAt),'time'),el('p',r.purpose));
  if(state.user.role==='ADMIN')card.append(el('p','申请用户 #'+r.userId,'hint'));
  if(r.deadline)card.append(el('p','处理截止：'+date(r.deadline),'hint'));
  const actions=el('div',undefined,'actions');
  const act=(text,name,body)=>actions.append(button(text,async b=>{b.disabled=true;try{await api('/reservations/'+r.id+'/'+name,'POST',body);toast('操作成功');await refresh();}finally{b.disabled=false;}}));
  if(r.status==='OFFERED')act('确认候补','accept');
  if(r.status==='PENDING_APPROVAL'&&state.user.role==='ADMIN'){
   act('批准','decision',{approve:true,reason:'管理员批准使用'});
   actions.append(button('拒绝',async()=>{const reason=prompt('请输入拒绝原因');if(!reason?.trim())return;await api('/reservations/'+r.id+'/decision','POST',{approve:false,reason});await refresh();}));
  }
  if(r.status==='CONFIRMED')act('签到使用','check-in');if(r.status==='IN_USE')act('结束使用','finish');
  if(['WAITLISTED','OFFERED','PENDING_APPROVAL','CONFIRMED'].includes(r.status))actions.append(button('取消',async()=>{if(!confirm('确认取消预约？'))return;await api('/reservations/'+r.id+'/cancel','POST');await refresh();}));
  actions.append(button('流转记录',async()=>{const rows=await api('/reservations/'+r.id+'/audit');$('audit-list').replaceChildren(...rows.map(a=>{const row=el('article',undefined,'notice');row.append(el('strong',(a.from_status?labels[a.from_status]+' → ':'')+labels[a.to_status]),el('p',a.reason),el('small',date(a.created_at),'hint'));return row;}));$('audit-dialog').showModal();}));card.append(actions);root.append(card);
 }
}
async function loadNotifications(more=false){const rows=await api('/notifications?limit=50&offset='+(more?state.notifications.length:0));state.notifications=more?[...state.notifications,...rows]:rows;$('more-notifications').hidden=rows.length<50;const root=$('notifications');root.replaceChildren();if(!state.notifications.length)root.append(el('p','暂无通知','empty'));for(const n of state.notifications){const row=el('article',undefined,'notice');row.append(el('p',n.message),el('small',date(n.created_at),'hint'));if(!n.read_at)row.append(button('标为已读',async()=>{await api('/notifications/'+n.id+'/read','POST');await loadNotifications();}));root.append(row);}}
async function loadAdmin(){
 const s=await api('/admin/summary');$('summary').replaceChildren(el('h2','运行概览'),el('p','占用时段 '+s.activeSlots+' · 待送达事件 '+s.pendingEvents),el('p',s.byStatus.map(x=>labels[x.status]+' '+x.total).join(' / '),'hint'));
 $('training-equipment').replaceChildren(...state.equipment.map(e=>{const o=el('option',e.name);o.value=e.id;return o;}));await findUsers();
}
async function findUsers(){const users=await api('/admin/users?q='+encodeURIComponent($('user-search').value));$('training-user').replaceChildren(...users.map(u=>{const o=el('option',u.username+' (#'+u.id+')');o.value=u.id;return o;}));}
let authBusy=false;
$('auth-form').addEventListener('submit',e=>{e.preventDefault();run(async()=>{if(authBusy)return;authBusy=true;try{const session=await api('/auth/'+(state.register?'register':'login'),'POST',{username:$('username').value.trim(),password:$('password').value});state.token=session.token;sessionStorage.setItem('bookingToken',state.token);$('password').value='';await loadSession();}finally{authBusy=false;}});});
$('toggle-register').addEventListener('click',()=>{state.register=!state.register;$('auth-title').textContent=state.register?'创建用户账号':'登录工作台';$('auth-form').querySelector('[type=submit]').textContent=state.register?'注册并登录':'登录';$('toggle-register').textContent=state.register?'已有账号？登录':'还没有账号？注册';$('password').minLength=state.register?8:1;});
$('search-form').addEventListener('submit',e=>{e.preventDefault();run(()=>loadEquipment());});
$('refresh').addEventListener('click',()=>run(refresh));
$('more-equipment').addEventListener('click',()=>run(()=>loadEquipment(true)));
$('more-reservations').addEventListener('click',()=>run(()=>loadReservations(true)));
$('more-notifications').addEventListener('click',()=>run(()=>loadNotifications(true)));
$('tabs').addEventListener('click',e=>{const tab=e.target.dataset.tab;if(!tab)return;for(const b of $('tabs').querySelectorAll('button')){b.classList.toggle('active',b.dataset.tab===tab);$('tab-'+b.dataset.tab).hidden=b.dataset.tab!==tab;}run(refresh);});
document.querySelectorAll('[data-close]').forEach(b=>b.addEventListener('click',()=>$(b.dataset.close).close()));
$('load-schedule').addEventListener('click',()=>run(async()=>{const start=new Date($('start-at').value);start.setHours(0,0,0,0);const end=new Date(start);end.setDate(end.getDate()+1);const rows=await api('/equipment/'+state.selected.id+'/schedule?'+new URLSearchParams({from:utcInput(start),to:utcInput(end)}));$('schedule').replaceChildren(...(rows.length?rows.map(r=>el('span',date(r.slot_at))):[el('span','当天暂无占用时段')]));}));
let bookingBusy=false;
$('booking-form').addEventListener('submit',e=>{e.preventDefault();run(async()=>{if(bookingBusy)return;bookingBusy=true;const submit=$('booking-form').querySelector('[type=submit]');submit.disabled=true;try{
 const payload={equipmentId:state.selected.id,startAt:utcInput($('start-at').value),endAt:utcInput($('end-at').value),purpose:$('purpose').value.trim()};
 if(!payload.purpose)throw new Error('请填写使用目的');const signature=JSON.stringify(payload);
 if(!state.draft||state.draft.signature!==signature)state.draft={signature,key:crypto.randomUUID()};
 const r=await api('/reservations','POST',{...payload,requestKey:state.draft.key});$('booking-dialog').close();toast('预约 #'+r.id+' · '+labels[r.status]);await refresh();
 }finally{bookingBusy=false;submit.disabled=false;}});});
$('equipment-form').addEventListener('submit',e=>{e.preventDefault();run(async()=>{const f=new FormData(e.target);await api('/equipment','POST',{name:f.get('name'),category:f.get('category'),location:f.get('location'),description:f.get('description'),approvalRequired:f.has('approvalRequired'),trainingRequired:f.has('trainingRequired')});e.target.reset();toast('设备已创建');await refresh();});});
$('find-user').addEventListener('click',()=>run(findUsers));
$('training-form').addEventListener('submit',e=>{e.preventDefault();run(async()=>{const granted=$('training-granted').value==='true';if(!granted&&!confirm('撤销资格会取消受影响的预约，确认继续？'))return;await api('/equipment/'+$('training-equipment').value+'/training','PUT',{userId:Number($('training-user').value),granted});toast('培训资格已更新');await refresh();});});
if(state.token)run(loadSession);
