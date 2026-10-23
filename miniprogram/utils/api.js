const DEFAULT_BASE = 'http://127.0.0.1:8080';
function base() { return wx.getStorageSync('server') || DEFAULT_BASE; }
function configure(url) {
  url = url.trim().replace(/\/+$/, '');
  if (!/^https?:\/\/[^/?#]+(?::\d+)?$/.test(url)) throw new Error('请输入服务地址，例如 https://booking.example.com，不含路径');
  if (url !== base()) { wx.removeStorageSync('session'); wx.removeStorageSync('draft'); }
  wx.setStorageSync('server', url);
}
function session() { return wx.getStorageSync('session'); }
function request(path, method = 'GET', data) {
  const auth = session(); const server = base();
  return new Promise((resolve, reject) => wx.request({
    url: server + '/api' + path, method, data, timeout: 15000,
    header: { 'content-type': 'application/json', ...(auth ? { Authorization: 'Bearer ' + auth.token } : {}) },
    success(res) {
      if (server !== base() || (auth && (!session() || auth.token !== session().token))) {
        reject(new Error('会话已切换，请重新加载')); return;
      }
      if (res.statusCode >= 200 && res.statusCode < 300) { resolve(res.data); return; }
      if (res.statusCode === 401 && auth) {
        wx.removeStorageSync('session'); wx.removeStorageSync('draft');
        wx.reLaunch({ url: '/pages/login/login' });
      }
      reject(new Error((res.data && res.data.message) || '请求失败：' + res.statusCode));
    },
    fail() { reject(new Error('网络请求未完成；写入结果可能已生效，请刷新或以原内容重试')); }
  }));
}
function requireLogin() { if (session()) return true; wx.reLaunch({url:'/pages/login/login'}); return false; }
function keyFor(body) {
  const scope = base() + ':' + (session() || {}).token;
  const payload = JSON.stringify(body); const old = wx.getStorageSync('draft');
  if (old && old.scope === scope && old.payload === payload) return old.key;
  const key = 'mini_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2,12);
  wx.setStorageSync('draft', { scope, payload, key }); return key;
}
module.exports = { base, configure, session, request, requireLogin, keyFor };
