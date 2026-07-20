(function (global) {
  "use strict";

  const root = global.XP = global.XP || {};
  const profileKey = "xplanet.profile";
  let profile = null;
  try {
    profile = JSON.parse(global.localStorage.getItem(profileKey) || "null");
  } catch (_) {
    global.localStorage.removeItem(profileKey);
  }

  async function login(username, password) {
    const data = await root.api.request("/api/user/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: { username: username, password: password }
    });
    if (!data || !data.token) throw new root.api.ApiError("登录响应中没有 Token", "TOKEN_MISSING", 200);
    root.api.setToken(data.token);
    profile = {
      username: username,
      nickname: data.nickname || username,
      userId: data.userId || data.id || null
    };
    global.localStorage.setItem(profileKey, JSON.stringify(profile));
    return profile;
  }

  function logout() {
    root.api.setToken("");
    profile = null;
    global.localStorage.removeItem(profileKey);
  }

  function currentProfile() {
    return profile;
  }

  root.auth = {
    login: login,
    logout: logout,
    currentProfile: currentProfile,
    isLoggedIn: function () { return Boolean(root.api.state.token); }
  };
})(window);
