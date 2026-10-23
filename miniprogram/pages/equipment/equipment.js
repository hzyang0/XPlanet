const api=require('../../utils/api');
Page({data:{items:[],q:'',error:'',busy:false,more:false},onShow(){if(api.requireLogin())this.load(false);},input(e){this.setData({q:e.detail.value});},search(){this.load(false);},next(){this.load(true);},
 async load(append){if(this.data.busy)return;this.setData({busy:true,error:''});try{const offset=append?this.data.items.length:0;const rows=await api.request('/equipment?q='+encodeURIComponent(this.data.q)+'&limit=20&offset='+offset);this.setData({items:append?this.data.items.concat(rows):rows,more:rows.length===20});}catch(e){this.setData({error:e.message});}finally{this.setData({busy:false});}},
 book(e){wx.navigateTo({url:'/pages/book/book?id='+e.currentTarget.dataset.id});},
 async logout(){try{await api.request('/auth/logout','POST');}catch(e){}wx.removeStorageSync('session');wx.removeStorageSync('draft');wx.reLaunch({url:'/pages/login/login'});}
});
