const api=require('../../utils/api');
Page({data:{server:api.base(),username:'',password:'',error:'',busy:false},
 onShow(){if(api.session()) wx.switchTab({url:'/pages/equipment/equipment'});},
 input(e){this.setData({[e.currentTarget.dataset.field]:e.detail.value});},
 async submit(e){if(this.data.busy)return; this.setData({busy:true,error:''});
 try{api.configure(this.data.server);const result=await api.request('/auth/'+e.currentTarget.dataset.action,'POST',{username:this.data.username.trim(),password:this.data.password});
 wx.setStorageSync('session',result);this.setData({password:''});wx.switchTab({url:'/pages/equipment/equipment'});
 }catch(e){this.setData({error:e.message});}finally{this.setData({busy:false});}}
});
