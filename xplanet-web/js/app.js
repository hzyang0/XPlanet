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

  function renderInlineMarkdown(value) {
    const links = [];
    const tokenized = String(value || "").replace(/\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/gi,
      function (match, label, url) {
        const safeUrl = safeHttpUrl(url);
        if (!safeUrl) return match;
        const index = links.push('<a href="' + escapeHtml(safeUrl) + '" target="_blank" rel="noopener noreferrer">' +
          escapeHtml(label) + '</a>') - 1;
        return "\u0000LINK" + index + "\u0000";
      });
    let escaped = escapeHtml(tokenized)
      .replace(/`([^`]+)`/g, "<code>$1</code>")
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
    return escaped.replace(/\u0000LINK(\d+)\u0000/g, function (_, index) {
      return links[Number(index)] || "";
    });
  }

  function renderMarkdown(markdown) {
    const lines = String(markdown || "").split(/\r?\n/);
    const html = [];
    let listType = "";
    let paragraph = [];
    let codeLines = [];
    let inCodeBlock = false;

    function closeList() {
      if (listType) html.push("</" + listType + ">");
      listType = "";
    }

    function flushParagraph() {
      if (paragraph.length) html.push("<p>" + renderInlineMarkdown(paragraph.join(" ")) + "</p>");
      paragraph = [];
    }

    lines.forEach(function (rawLine) {
      const line = rawLine.trimEnd();
      if (/^```/.test(line.trim())) {
        flushParagraph();
        closeList();
        if (inCodeBlock) {
          html.push("<pre><code>" + escapeHtml(codeLines.join("\n")) + "</code></pre>");
          codeLines = [];
        }
        inCodeBlock = !inCodeBlock;
        return;
      }
      if (inCodeBlock) {
        codeLines.push(rawLine);
        return;
      }
      if (!line.trim()) {
        flushParagraph();
        closeList();
        return;
      }
      const heading = line.match(/^(#{1,3})\s+(.+)$/);
      const unordered = line.match(/^\s*[-*]\s+(.+)$/);
      const ordered = line.match(/^\s*\d+[.)]\s+(.+)$/);
      if (heading) {
        flushParagraph();
        closeList();
        const level = heading[1].length;
        html.push("<h" + level + ">" + renderInlineMarkdown(heading[2]) + "</h" + level + ">");
      } else if (/^\s*>\s?/.test(line)) {
        flushParagraph();
        closeList();
        html.push("<blockquote><p>" + renderInlineMarkdown(line.replace(/^\s*>\s?/, "")) + "</p></blockquote>");
      } else if (unordered || ordered) {
        flushParagraph();
        const nextType = unordered ? "ul" : "ol";
        if (listType !== nextType) {
          closeList();
          listType = nextType;
          html.push("<" + listType + ">");
        }
        html.push("<li>" + renderInlineMarkdown((unordered || ordered)[1]) + "</li>");
      } else if (/^\s*---+\s*$/.test(line)) {
        flushParagraph();
        closeList();
        html.push("<hr>");
      } else {
        closeList();
        paragraph.push(line.trim());
      }
    });
    flushParagraph();
    closeList();
    if (inCodeBlock && codeLines.length) html.push("<pre><code>" + escapeHtml(codeLines.join("\n")) + "</code></pre>");
    return html.join("");
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
    bind();
    const connection = await root.api.discoverBaseUrl();
    document.getElementById("apiBase").value = connection.baseUrl;
    const hint = document.getElementById("connectionHint");
    hint.textContent = connection.connected ? "已连接 " + connection.baseUrl : "未找到 Gateway，请检查地址或启动服务";
    hint.classList.toggle("is-error", !connection.connected);
    renderSession();
    activate(global.location.hash.slice(1));
    if (!connection.connected) {
      document.getElementById("articleList").innerHTML = '<div class="inline-error">Gateway 未连接。服务启动后刷新页面，或手动填写正确地址。</div>';
      return;
    }
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
