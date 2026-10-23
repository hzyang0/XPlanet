const api=require('../../utils/api');const time=require('../../utils/time');
Page({data:{id:null,reservationId:null,...time.defaults(),purpose:'',slots:[],error:'',busy:false,result:''},
 onLoad(options){this.setData({...time.defaults(),id:Number(options.id),reservationId:options.reservation?Number(options.reservation):null});
 const draft=wx.getStorageSync('draft');
 if(draft && draft.scope===api.base()+':'+(api.session()||{}).token){
  try{const saved=JSON.parse(draft.payload);const expected=this.data.reservationId?'/reservations/'+this.data.reservationId+'/reschedule':'/reservations';
   if(saved.path===expected && (this.data.reservationId || saved.body.equipmentId===this.data.id)){
    const start=new Date(saved.body.startAt),end=new Date(saved.body.endAt);
    this.setData({day:time.date(start),start:time.time(start),endDay:time.date(end),end:time.time(end),purpose:saved.body.purpose||''});
   }
  }catch(e){wx.removeStorageSync('draft');}
 }
},
 onShow(){if(api.requireLogin())this.schedule();},input(e){this.setData({[e.currentTarget.dataset.field]:e.detail.value,result:''});},
 async schedule(){this.setData({error:''});try{const from=time.iso(this.data.day,'00:00');const to=new Date(new Date(from).getTime()+86400000).toISOString();const rows=await api.request('/equipment/'+this.data.id+'/schedule?from='+encodeURIComponent(from)+'&to='+encodeURIComponent(to));this.setData({slots:rows.map(r=>({at:time.display(r.slot_at),kind:r.kind==='MAINTENANCE'?'维护':'已占用'}))});}catch(e){this.setData({error:e.message});}},
 async submit(){if(this.data.busy)return;this.setData({busy:true,error:'',result:''});try{
 const startAt=time.iso(this.data.day,this.data.start),endAt=time.iso(this.data.endDay,this.data.end);
 if(new Date(startAt)<=new Date() || new Date(endAt)<=new Date(startAt))throw new Error('开始时间须在未来，结束时间须晚于开始时间');
 const path=this.data.reservationId?'/reservations/'+this.data.reservationId+'/reschedule':'/reservations';
 const body=this.data.reservationId?{startAt,endAt}:{equipmentId:this.data.id,startAt,endAt,purpose:this.data.purpose.trim()};
 if(!this.data.reservationId&&!body.purpose)throw new Error('请填写实验用途');
 const requestKey=api.keyFor({path,body});const r=await api.request(path,'POST',{...body,requestKey});
 // Keep this key until the payload changes: a repeated tap after success replays the same booking.
 this.setData({result:'预约 #'+r.id+'：'+time.reservation(r).label});this.schedule();
 }catch(e){this.setData({error:e.message});}finally{this.setData({busy:false});}},
 mine(){wx.switchTab({url:'/pages/reservations/reservations'});}
});
