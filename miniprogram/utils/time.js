function pad(n) { return String(n).padStart(2,'0'); }
function date(d) { return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate()); }
function time(d) { return pad(d.getHours())+':'+pad(d.getMinutes()); }
function iso(day, hour) {
  const parts = (day+'-'+hour.replace(':','-')).split('-').map(Number);
  const d = new Date(parts[0],parts[1]-1,parts[2],parts[3],parts[4]);
  if (!Number.isFinite(d.getTime()) || date(d)!==day || time(d)!==hour) throw new Error('日期或时间无效');
  if (d.getMinutes()%30) throw new Error('请选择整点或半点');
  return d.toISOString();
}
function display(value) { if (!value) return ''; const d=new Date(value); return date(d)+' '+time(d); }
function defaults() { const d=new Date(Math.ceil((Date.now()+1800000)/1800000)*1800000); const e=new Date(d.getTime()+3600000); return {day:date(d),start:time(d),endDay:date(e),end:time(e)}; }
const labels={WAITLISTED:'候补中',OFFERED:'待确认补位',PENDING_APPROVAL:'待审批',CONFIRMED:'已确认',IN_USE:'使用中',COMPLETED:'已完成',CANCELLED:'已取消',REJECTED:'已拒绝',EXPIRED:'已过期',NO_SHOW:'未签到'};
function reservation(r) { return Object.assign({},r,{label:labels[r.status]||r.status,period:display(r.startAt)+' 至 '+display(r.endAt),deadlineText:display(r.deadline),cancelable:['WAITLISTED','OFFERED','PENDING_APPROVAL','CONFIRMED'].includes(r.status)}); }
module.exports={date,time,iso,display,defaults,reservation};
