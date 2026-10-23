const api=require('../../utils/api');const time=require('../../utils/time');
Page({data:{items:[],error:'',busy:false,more:false},onShow(){if(api.requireLogin())this.load(false);},refresh(){this.load(false);},next(){this.load(true);},
 async load(append){if(this.data.busy)return;this.setData({busy:true,error:''});try{const rows=await api.request('/notifications?limit=20&offset='+(append?this.data.items.length:0));this.setData({items:(append?this.data.items:[]).concat(rows.map(r=>({...r,at:time.display(r.created_at)}))),more:rows.length===20});}catch(e){this.setData({error:e.message});}finally{this.setData({busy:false});}},
 async read(e){try{await api.request('/notifications/'+e.currentTarget.dataset.id+'/read','POST');this.load(false);}catch(e){this.setData({error:e.message});}}
});
