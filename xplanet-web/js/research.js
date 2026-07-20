(function (global) {
  "use strict";

  const root = global.XP = global.XP || {};
  const activeStatuses = new Set(["QUEUED", "RUNNING", "RETRYING"]);
  const state = {
    tasks: [],
    selectedTask: null,
    report: null,
    events: new Map(),
    lastEventId: "",
    streamController: null,
    reconnectTimer: null,
    statusPollTimer: null
  };

  function integer(value, fallback) {
    const number = Number(value);
    return Number.isFinite(number) ? Math.trunc(number) : (fallback || 0);
  }

  function statusTone(status) {
    if (["SUCCEEDED", "PUBLISHED", "APPROVED"].includes(status)) return "success";
    if (["WAITING_REVIEW", "RETRYING", "DRAFT", "QUEUED"].includes(status)) return "warning";
    if (["FAILED", "CANCELLED"].includes(status)) return "danger";
    return "info";
  }

  function setBadge(element, status) {
    element.textContent = status || "UNKNOWN";
    element.dataset.tone = statusTone(status);
  }

  function setAuthenticated(authenticated) {
    document.getElementById("researchAuthGate").hidden = authenticated;
    document.getElementById("researchWorkspace").hidden = !authenticated;
    if (!authenticated) {
      stopStream();
      state.tasks = [];
      state.selectedTask = null;
      state.events.clear();
      renderTasks();
      renderTaskDetail();
      renderReport(null);
    }
  }

  async function refreshTasks(options) {
    if (!root.auth.isLoggedIn()) return;
    const preserve = !(options && options.clearSelection);
    try {
      state.tasks = await root.api.request("/api/ai/tasks?limit=50") || [];
      if (preserve && state.selectedTask) {
        const latest = state.tasks.find(function (task) { return task.id === state.selectedTask.id; });
        if (latest) state.selectedTask = latest;
      }
      renderTasks();
      renderTaskDetail();
    } catch (error) {
      document.getElementById("taskList").innerHTML = '<div class="inline-error">任务加载失败：' + root.util.escapeHtml(error.message) + '</div>';
      if (error.status === 401 || error.code === 2001) root.app.handleExpiredSession();
    }
  }

  function renderTasks() {
    const box = document.getElementById("taskList");
    if (!state.tasks.length) {
      box.innerHTML = '<div class="empty-copy">还没有研究任务</div>';
      return;
    }
    box.innerHTML = state.tasks.map(function (task) {
      const selected = state.selectedTask && state.selectedTask.id === task.id;
      return '<button class="task-card' + (selected ? ' is-active' : '') + '" data-task-id="' + task.id + '" type="button">' +
        '<div class="task-card-top"><strong>#' + task.id + '</strong><span class="status-badge" data-tone="' + statusTone(task.status) + '">' +
        root.util.escapeHtml(task.status) + '</span></div>' +
        '<p>' + root.util.escapeHtml(task.question) + '</p><time>' + root.util.escapeHtml(root.util.formatTime(task.updateTime || task.createTime)) + '</time></button>';
    }).join("");
  }

  async function createTask(event) {
    event.preventDefault();
    if (!root.auth.isLoggedIn()) {
      root.app.toast("请先登录", true);
      return;
    }
    const button = document.getElementById("createTaskButton");
    const question = document.getElementById("researchQuestion").value.trim();
    if (!question) {
      root.app.toast("请输入研究问题", true);
      return;
    }
    button.disabled = true;
    button.textContent = "正在创建…";
    try {
      const task = await root.api.request("/api/ai/tasks", {
        method: "POST",
        headers: { "Idempotency-Key": global.crypto && global.crypto.randomUUID ? global.crypto.randomUUID() : String(Date.now()) },
        body: {
          question: question,
          maxSources: integer(document.getElementById("maxSources").value, 5),
          maxToolCalls: integer(document.getElementById("maxToolCalls").value, 10),
          maxTokens: integer(document.getElementById("maxTokens").value, 8000),
          deadlineSeconds: integer(document.getElementById("deadlineSeconds").value, 300)
        }
      });
      document.getElementById("researchQuestion").value = "";
      await refreshTasks();
      await selectTask(task.id);
      root.app.toast("研究任务已可靠入队");
    } catch (error) {
      root.app.toast("创建失败：" + error.message, true);
    } finally {
      button.disabled = false;
      button.textContent = "开始研究";
    }
  }

  async function selectTask(taskId) {
    const id = integer(taskId);
    if (!id) return;
    stopStream();
    const changed = !state.selectedTask || state.selectedTask.id !== id;
    if (changed) {
      state.events.clear();
      state.lastEventId = "";
      state.report = null;
      renderTimeline();
      renderReport(null);
    }
    try {
      state.selectedTask = await root.api.request("/api/ai/tasks/" + id);
      renderTasks();
      renderTaskDetail();
      if (["WAITING_REVIEW", "SUCCEEDED"].includes(state.selectedTask.status)) await loadReport();
      connectStream();
    } catch (error) {
      root.app.toast("任务加载失败：" + error.message, true);
    }
  }

  function renderTaskDetail() {
    const empty = document.getElementById("taskEmpty");
    const detail = document.getElementById("taskDetail");
    const task = state.selectedTask;
    empty.hidden = Boolean(task);
    detail.hidden = !task;
    if (!task) return;
    setBadge(document.getElementById("taskStatus"), task.status);
    document.getElementById("taskRunId").textContent = task.currentRunId || "";
    document.getElementById("taskQuestion").textContent = task.question || "";
    document.getElementById("taskSourceBudget").textContent = "来源 ≤ " + task.maxSources;
    document.getElementById("taskToolBudget").textContent = "工具 ≤ " + task.maxToolCalls;
    document.getElementById("taskTokenBudget").textContent = "Token ≤ " + task.maxTokens;
    document.getElementById("taskDeadline").textContent = "截止 " + task.deadlineSeconds + "s";
    const progress = latestProgress();
    document.getElementById("taskProgressText").textContent = progress + "%";
    document.getElementById("taskProgressBar").style.width = progress + "%";
    const cancelButton = document.getElementById("cancelTaskButton");
    cancelButton.hidden = ["SUCCEEDED", "FAILED", "CANCELLED"].includes(task.status);
    if (task.lastError && !state.events.has("task-error")) {
      state.events.set("task-error", {
        id: "task-error", node: "TASK", status: "FAILED", message: task.lastError,
        progress: progress, timestamp: Date.now()
      });
      renderTimeline();
    }
  }

  function latestProgress() {
    let progress = 0;
    state.events.forEach(function (event) { progress = Math.max(progress, integer(event.progress)); });
    if (state.selectedTask && ["WAITING_REVIEW", "SUCCEEDED"].includes(state.selectedTask.status)) progress = 100;
    return Math.min(100, Math.max(0, progress));
  }

  function handleSseEvent(raw) {
    if (raw.id) state.lastEventId = raw.id;
    if (raw.event === "heartbeat" || !raw.data) return;
    try {
      const data = JSON.parse(raw.data);
      const event = {
        id: raw.id || (data.runId + ":" + data.node + ":" + data.timestamp),
        node: data.node || "UNKNOWN",
        status: data.status || "INFO",
        message: data.message || "",
        progress: integer(data.progress),
        timestamp: integer(data.timestamp, Date.now())
      };
      state.events.set(event.id, event);
      renderTimeline();
      renderTaskDetail();
      if (event.progress >= 100) scheduleStatusRefresh();
    } catch (_) {
      // Ignore malformed progress data; the durable task status remains authoritative.
    }
  }

  function renderTimeline() {
    const box = document.getElementById("eventTimeline");
    const events = Array.from(state.events.values());
    if (!events.length) {
      box.innerHTML = '<li class="empty-copy">任务入队后，节点事件会在这里实时出现。</li>';
      return;
    }
    box.innerHTML = events.map(function (event) {
      const failed = event.status === "FAILED";
      return '<li class="timeline-item"><span class="timeline-dot" style="background:' + (failed ? 'var(--red)' : 'var(--forest)') + '">' +
        (failed ? '!' : '✓') + '</span><div class="timeline-body"><div class="timeline-title"><span>' +
        root.util.escapeHtml(event.node) + ' · ' + root.util.escapeHtml(event.status) + '</span><time>' +
        root.util.escapeHtml(root.util.formatTime(event.timestamp)) + '</time></div><p>' +
        root.util.escapeHtml(event.message) + '</p></div></li>';
    }).join("");
    box.lastElementChild && box.lastElementChild.scrollIntoView({ block: "nearest" });
  }

  function connectStream() {
    if (!state.selectedTask || !root.auth.isLoggedIn()) return;
    stopStream();
    const taskId = state.selectedTask.id;
    const controller = new AbortController();
    state.streamController = controller;
    setStreamState("实时连接中", true);
    root.api.stream("/api/ai/tasks/" + taskId + "/events", {
      lastEventId: state.lastEventId,
      signal: controller.signal,
      onEvent: handleSseEvent
    }).then(async function () {
      if (controller.signal.aborted || !state.selectedTask || state.selectedTask.id !== taskId) return;
      await refreshSelectedTaskAndReport();
      if (state.selectedTask && activeStatuses.has(state.selectedTask.status)) scheduleReconnect();
      else setStreamState("执行流已结束", false);
    }).catch(function (error) {
      if (error.name === "AbortError" || controller.signal.aborted) return;
      setStreamState("连接中断，准备重连", false);
      if (state.selectedTask && activeStatuses.has(state.selectedTask.status)) scheduleReconnect();
    });
  }

  function scheduleReconnect() {
    global.clearTimeout(state.reconnectTimer);
    state.reconnectTimer = global.setTimeout(connectStream, 1200);
  }

  function stopStream() {
    global.clearTimeout(state.reconnectTimer);
    global.clearTimeout(state.statusPollTimer);
    state.reconnectTimer = null;
    state.statusPollTimer = null;
    if (state.streamController) state.streamController.abort();
    state.streamController = null;
    setStreamState("未连接", false);
  }

  function setStreamState(text, live) {
    const element = document.getElementById("streamState");
    if (!element) return;
    element.textContent = text;
    element.classList.toggle("is-live", Boolean(live));
  }

  async function refreshSelectedTaskAndReport() {
    if (!state.selectedTask) return;
    const taskId = state.selectedTask.id;
    try {
      state.selectedTask = await root.api.request("/api/ai/tasks/" + taskId);
      const index = state.tasks.findIndex(function (item) { return item.id === taskId; });
      if (index >= 0) state.tasks[index] = state.selectedTask;
      renderTasks();
      renderTaskDetail();
      if (["WAITING_REVIEW", "SUCCEEDED"].includes(state.selectedTask.status)) await loadReport();
      if (activeStatuses.has(state.selectedTask.status) && latestProgress() >= 100) scheduleStatusRefresh();
    } catch (_) { /* next reconnect or manual refresh will retry */ }
  }

  function scheduleStatusRefresh() {
    global.clearTimeout(state.statusPollTimer);
    state.statusPollTimer = global.setTimeout(refreshSelectedTaskAndReport, 1000);
  }

  async function loadReport() {
    if (!state.selectedTask) return;
    try {
      state.report = await root.api.request("/api/ai/tasks/" + state.selectedTask.id + "/report");
      renderReport(state.report);
    } catch (error) {
      if (state.selectedTask.status === "WAITING_REVIEW" || state.selectedTask.status === "SUCCEEDED") {
        document.getElementById("reportEmpty").innerHTML = '<div class="inline-error">报告读取失败：' + root.util.escapeHtml(error.message) + '</div>';
      }
    }
  }

  function renderReport(report) {
    document.getElementById("reportEmpty").hidden = Boolean(report);
    document.getElementById("reportDetail").hidden = !report;
    if (!report) return;
    setBadge(document.getElementById("reportStatus"), report.status);
    document.getElementById("reportTitle").value = report.title || "";
    document.getElementById("reportContent").value = report.content || "";
    document.getElementById("reportPreview").innerHTML = root.util.renderMarkdown(report.content || "");
    document.getElementById("qualityScore").textContent = "质量分 " + (report.qualityScore == null ? "-" : Number(report.qualityScore).toFixed(2));
    document.getElementById("sourceCount").textContent = "来源 " + (report.sources || []).length;
    document.getElementById("evidenceCount").textContent = "证据 " + (report.evidence || []).length;
    document.getElementById("citationCount").textContent = "引用 " + (report.citations || []).length;
    renderSources(report.sources || []);
    renderEvidence(report.evidence || [], report.citations || []);
    renderUsage(report.usage || []);
    const published = Boolean(report.publishArticleId);
    document.getElementById("approveReportButton").hidden = published;
    const openButton = document.getElementById("openArticleButton");
    openButton.hidden = !published;
    openButton.dataset.articleId = published ? report.publishArticleId : "";
  }

  function renderSources(sources) {
    const box = document.getElementById("sourceList");
    box.innerHTML = sources.length ? sources.map(function (source, index) {
      const url = root.util.safeHttpUrl(source.url);
      return '<div class="source-card"><small>SOURCE ' + (index + 1) + ' · #' + source.id + '</small>' +
        (url ? '<a href="' + root.util.escapeHtml(url) + '" target="_blank" rel="noopener noreferrer">' + root.util.escapeHtml(source.title || url) + '</a>' :
          '<b>' + root.util.escapeHtml(source.title || "未知来源") + '</b>') +
        '<small>' + root.util.escapeHtml(source.contentHash || "") + '</small></div>';
    }).join("") : '<div class="empty-copy">没有来源</div>';
  }

  function renderEvidence(evidence, citations) {
    const bindings = new Map();
    citations.forEach(function (citation) {
      const list = bindings.get(citation.evidenceId) || [];
      list.push(citation.claimId + " (" + Number(citation.supportScore || 0).toFixed(2) + ")");
      bindings.set(citation.evidenceId, list);
    });
    const box = document.getElementById("evidenceList");
    box.innerHTML = evidence.length ? evidence.map(function (item) {
      const claims = bindings.get(item.id) || [];
      return '<div class="evidence-card"><small>EVIDENCE #' + item.id + ' · SOURCE #' + item.sourceId +
        ' · SCORE ' + Number(item.score || 0).toFixed(2) + '</small><p>' + root.util.escapeHtml(item.content || "") + '</p>' +
        '<small>' + root.util.escapeHtml(item.locator || "") + (claims.length ? ' · CLAIM ' + root.util.escapeHtml(claims.join(", ")) : '') + '</small></div>';
    }).join("") : '<div class="empty-copy">没有 Evidence</div>';
  }

  function renderUsage(usage) {
    const box = document.getElementById("usageList");
    if (!usage.length) {
      box.innerHTML = '<div class="empty-copy">offline-demo 可能没有模型用量；联网运行后会显示。</div>';
      return;
    }
    box.innerHTML = usage.map(function (item) {
      return '<div class="usage-card"><b>' + root.util.escapeHtml(item.nodeName) + '</b><br><small>' +
        root.util.escapeHtml(item.provider) + ' / ' + root.util.escapeHtml(item.model) +
        ' · IN ' + integer(item.inputTokens) + ' · OUT ' + integer(item.outputTokens) +
        ' · ' + integer(item.latencyMs) + 'ms · COST ' + Number(item.estimatedCost || 0).toFixed(6) + '</small></div>';
    }).join("");
  }

  async function cancelTask() {
    if (!state.selectedTask) return;
    const button = document.getElementById("cancelTaskButton");
    button.disabled = true;
    try {
      state.selectedTask = await root.api.request("/api/ai/tasks/" + state.selectedTask.id, { method: "DELETE" });
      stopStream();
      await refreshTasks();
      renderTaskDetail();
      root.app.toast("取消命令已可靠入队");
    } catch (error) {
      root.app.toast("取消失败：" + error.message, true);
    } finally {
      button.disabled = false;
    }
  }

  async function approveReport() {
    if (!state.selectedTask || !state.report) return;
    const button = document.getElementById("approveReportButton");
    const title = document.getElementById("reportTitle").value.trim();
    const content = document.getElementById("reportContent").value.trim();
    if (!title || !content) {
      root.app.toast("标题和正文不能为空", true);
      return;
    }
    button.disabled = true;
    button.textContent = "正在发布…";
    try {
      state.report = await root.api.request("/api/ai/tasks/" + state.selectedTask.id + "/report/approve", {
        method: "POST",
        body: { title: title, content: content }
      });
      await refreshSelectedTaskAndReport();
      root.app.toast("报告已审核并幂等发布");
    } catch (error) {
      root.app.toast("发布失败：" + error.message, true);
    } finally {
      button.disabled = false;
      button.textContent = "审核并发布文章";
    }
  }

  function bind() {
    document.getElementById("researchForm").addEventListener("submit", createTask);
    document.getElementById("refreshTasksButton").addEventListener("click", function () { refreshTasks(); });
    document.getElementById("taskList").addEventListener("click", function (event) {
      const card = event.target.closest("[data-task-id]");
      if (card) selectTask(card.dataset.taskId);
    });
    document.getElementById("cancelTaskButton").addEventListener("click", cancelTask);
    document.getElementById("approveReportButton").addEventListener("click", approveReport);
    document.getElementById("openArticleButton").addEventListener("click", function (event) {
      root.community.openArticle(event.currentTarget.dataset.articleId);
    });
    document.getElementById("reportContent").addEventListener("input", function (event) {
      document.getElementById("reportPreview").innerHTML = root.util.renderMarkdown(event.target.value);
    });
  }

  root.research = {
    bind: bind,
    setAuthenticated: setAuthenticated,
    refreshTasks: refreshTasks,
    selectTask: selectTask,
    stopStream: stopStream
  };
})(window);
