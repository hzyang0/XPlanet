const api=require('../../utils/api');const time=require('../../utils/time');
Page({data:{items:[],error:'',busy:false,more:false},onShow(){if(api.requireLogin())this.load(false);},refresh(){this.load(false);},next(){this.load(true);},
 async load(append){if(this.data.busy)return;this.setData({busy:true,error:''});try{const rows=await api.request('/reservations?limit=20&offset='+(append?this.data.items.length:0));this.setData({items:(append?this.data.items:[]).concat(rows.map(time.reservation)),more:rows.length===20});}catch(e){this.setData({error:e.message});}finally{this.setData({busy:false});}},
 action(e){const {id,action}=e.currentTarget.dataset;if(this.data.busy)return;wx.showModal({title:'确认操作',content:'是否执行 '+action+'？服务器会核验当前状态和操作权限。',success:async result=>{if(!result.confirm||this.data.busy)return;this.setData({busy:true,error:''});try{await api.request('/reservations/'+id+'/'+action,'POST');}catch(e){this.setData({error:e.message});return;}finally{this.setData({busy:false});}this.load(false);}});},
 reschedule(e){wx.navigateTo({url:'/pages/book/book?id='+e.currentTarget.dataset.equipment+'&reservation='+e.currentTarget.dataset.id});}
});
