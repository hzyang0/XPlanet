(function (global) {
  "use strict";

  const root = global.XP = global.XP || {};
  let toastTimer = null;

  function escapeHtml(value) {
    return String(value == null ? "" : value).replace(/[&<>"']/g, function (character) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[character];
    });
  }

  function safeHttpUrl(value) {
    try {
      const url = new URL(String(value || ""));
      return ["http:", "https:"].includes(url.protocol) ? url.href : "";
    } catch (_) {
      return "";
    }
  }

  function formatTime(value) {
    if (value == null || value === "") return "";
    const numeric = Number(value);
    const date = Number.isFinite(numeric) && numeric > 0 ? new Date(numeric) : new Date(value);
    if (Number.isNaN(date.getTime())) return String(value).replace("T", " ").slice(0, 19);
    return new Intl.DateTimeFormat("zh-CN", {
      month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit"
    }).format(date);
  }

  function renderMarkdown(markdown) {
    const lines = String(markdown || "").split(/\r?\n/);
    return lines.map(function (line) {
      let escaped = escapeHtml(line);
      escaped = escaped.replace(/`([^`]+)`/g, "<code>$1</code>")
        .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
      if (escaped.startsWith("### ")) return "<h3>" + escaped.slice(4) + "</h3>";
      if (escaped.startsWith("## ")) return "<h2>" + escaped.slice(3) + "</h2>";
      if (escaped.startsWith("# ")) return "<h1>" + escaped.slice(2) + "</h1>";
      if (/^[-*] /.test(escaped)) return "<p>• " + escaped.slice(2) + "</p>";
      return escaped ? "<p>" + escaped + "</p>" : "<br>";
    }).join("");
  }

  function toast(message, error) {
    const element = document.getElementById("toast");
    element.textContent = message;
    element.classList.toggle("is-error", Boolean(error));
    element.classList.add("is-visible");
    global.clearTimeout(toastTimer);
    toastTimer = global.setTimeout(function () { element.classList.remove("is-visible"); }, 2600);
  }

  function activate(viewName) {
    const name = viewName === "community" ? "community" : "research";
    document.querySelectorAll(".view").forEach(function (view) {
      view.classList.toggle("is-active", view.id === name + "View");
    });
    document.querySelectorAll(".tab").forEach(function (tab) {
      tab.classList.toggle("is-active", tab.dataset.view === name);
    });
    if (global.location.hash !== "#" + name) global.history.replaceState(null, "", "#" + name);
  }

  function renderSession() {
    const loggedIn = root.auth.isLoggedIn();
    const profile = root.auth.currentProfile();
    const state = document.getElementById("loginState");
    state.textContent = loggedIn ? "已登录 · " + (profile && profile.nickname ? profile.nickname : "演示用户") : "未登录";
    state.style.color = loggedIn ? "var(--forest)" : "var(--ink-soft)";
    document.getElementById("logoutButton").hidden = !loggedIn;
    document.getElementById("loginButton").hidden = loggedIn;
    root.research.setAuthenticated(loggedIn);
  }

  async function login(event) {
    if (event) event.preventDefault();
    const button = document.getElementById("loginButton");
    const username = document.getElementById("loginUser").value.trim();
    const password = document.getElementById("loginPassword").value;
    if (!username || !password) {
      toast("请输入用户名和密码", true);
      return;
    }
    try {
      root.api.setBaseUrl(document.getElementById("apiBase").value);
      button.disabled = true;
      button.textContent = "登录中…";
      await root.auth.login(username, password);
      renderSession();
      await root.research.refreshTasks();
      toast("登录成功，已进入私有研究空间");
    } catch (error) {
      toast("登录失败：" + error.message, true);
    } finally {
      button.disabled = false;
      button.textContent = "登录";
    }
  }

  function logout() {
    root.research.stopStream();
    root.auth.logout();
    renderSession();
    toast("已退出登录");
  }

  function handleExpiredSession() {
    root.auth.logout();
    renderSession();
    toast("登录已失效，请重新登录", true);
  }

  function bind() {
    document.querySelectorAll(".tab").forEach(function (tab) {
      tab.addEventListener("click", function () { activate(tab.dataset.view); });
    });
    document.getElementById("loginForm").addEventListener("submit", login);
    document.getElementById("logoutButton").addEventListener("click", logout);
    document.getElementById("apiBase").addEventListener("change", function (event) {
      try {
        root.api.setBaseUrl(event.target.value);
        toast("Gateway 地址已保存");
      } catch (error) {
        toast(error.message, true);
      }
    });
    global.addEventListener("hashchange", function () { activate(global.location.hash.slice(1)); });
    root.community.bind();
    root.research.bind();
  }

  async function bootstrap() {
    document.getElementById("apiBase").value = root.api.state.baseUrl;
    bind();
    renderSession();
    activate(global.location.hash.slice(1));
    await root.community.load();
    if (root.auth.isLoggedIn()) await root.research.refreshTasks();
  }

  root.util = {
    escapeHtml: escapeHtml,
    safeHttpUrl: safeHttpUrl,
    formatTime: formatTime,
    renderMarkdown: renderMarkdown
  };
  root.app = {
    toast: toast,
    activate: activate,
    handleExpiredSession: handleExpiredSession
  };

  document.addEventListener("DOMContentLoaded", bootstrap);
})(window);
