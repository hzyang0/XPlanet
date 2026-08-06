(function (global) {
  "use strict";

  const root = global.XP = global.XP || {};
  const activeStatuses = new Set(["QUEUED", "RUNNING", "RETRYING"]);
  const workflowPhases = [
    { label: "输入与规划", nodes: ["VALIDATE_INPUT", "PLAN_CREATED"] },
    { label: "检索与工具", nodes: ["DECIDE_ACTION", "TOOL_STARTED", "TOOL_COMPLETED"] },
    { label: "证据整理", nodes: ["EVIDENCE_ADDED"] },
    { label: "报告写作", nodes: ["WRITER"] },
    { label: "质量审计", nodes: ["CRITIC"] },
    { label: "生成完成", nodes: ["FINALIZE"] }
  ];
  const state = {
    tasks: [],
    selectedTask: null,
    report: null,
    events: new Map(),
    lastEventId: "",
    streamController: null,
    reconnectTimer: null,
    statusPollTimer: null,
    providerCapabilities: null
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
      await refreshProviderCapabilities();
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

  async function refreshProviderCapabilities() {
    const select = document.getElementById("researchProvider");
    const hint = document.getElementById("providerHint");
    try {
      state.providerCapabilities = await root.api.request("/api/ai/providers");
      const providers = state.providerCapabilities.providers || {};
      const online = Boolean(providers["openai-tools"]);
      const onlineOption = select.querySelector('option[value="openai-tools"]');
      onlineOption.disabled = !online;
      if (!online && select.value === "openai-tools") select.value = "offline-demo";
      hint.textContent = online
        ? "在线模式已就绪：由 Agent 在服务端读取 API Key，浏览器不会接触密钥。"
        : "离线模式可用；要启用在线模式，请配置 OPENAI_API_KEY 后重启 Agent。";
      hint.classList.toggle("is-online", online);
    } catch (_) {
      state.providerCapabilities = null;
      select.value = "offline-demo";
      select.querySelector('option[value="openai-tools"]').disabled = true;
      hint.textContent = "暂时无法读取 Agent 能力，已安全回退到离线模式。";
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
        '<p>' + root.util.escapeHtml(task.question) + '</p><div class="task-card-foot"><span class="provider-badge">' +
        root.util.escapeHtml(task.provider || "offline-demo") + '</span><time>' + root.util.escapeHtml(root.util.formatTime(task.updateTime || task.createTime)) + '</time></div></button>';
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
          provider: document.getElementById("researchProvider").value,
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
    document.getElementById("taskProvider").textContent = "模式 " + (task.provider || "offline-demo");
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
    const flow = document.getElementById("phaseFlow");
    const summary = document.getElementById("traceSummary");
    const events = Array.from(state.events.values());
    flow.innerHTML = workflowPhases.map(function (phase, index) {
      const phaseEvents = events.filter(function (event) { return phase.nodes.includes(event.node); });
      const latest = phaseEvents[phaseEvents.length - 1];
      const failed = phaseEvents.some(function (event) { return event.status === "FAILED"; });
      const running = latest && ["RUNNING", "QUEUED", "RETRYING"].includes(latest.status);
      const tone = failed ? "failed" : running ? "running" : phaseEvents.length ? "completed" : "pending";
      const marker = failed ? "!" : running ? "…" : phaseEvents.length ? "✓" : String(index + 1);
      const detail = latest ? latest.message : "等待前序阶段";
      return '<div class="phase-card is-' + tone + '"><span class="phase-marker">' + marker + '</span>' +
        '<div><b>' + root.util.escapeHtml(phase.label) + '</b><small>' +
        root.util.escapeHtml(detail) + (phaseEvents.length > 1 ? " · " + phaseEvents.length + " 个事件" : "") +
        '</small></div></div>';
    }).join("");
    summary.textContent = "查看 " + events.length + " 条原始节点事件";
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
    document.getElementById("researchWorkspace").classList.toggle("has-report", Boolean(report));
    document.getElementById("reportEmpty").hidden = Boolean(report);
    document.getElementById("reportDetail").hidden = !report;
    if (!report) return;
    setBadge(document.getElementById("reportStatus"), report.status);
    document.getElementById("reportTitle").value = report.title || "";
    document.getElementById("reportContent").value = report.content || "";
    document.getElementById("reportPreview").innerHTML = root.util.renderMarkdown(report.content || "");
    document.getElementById("qualityScore").textContent = report.qualityScore == null ? "-" : Number(report.qualityScore).toFixed(2);
    document.getElementById("sourceCount").textContent = (report.sources || []).length;
    document.getElementById("evidenceCount").textContent = (report.evidence || []).length;
    document.getElementById("citationCount").textContent = (report.citations || []).length;
    renderSources(report.sources || []);
    renderEvidence(report.evidence || [], report.citations || [], report.sources || []);
    renderCitations(report.citations || [], report.evidence || [], report.sources || []);
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
      const internalArticle = url && url.match(/\/api\/article\/(\d+)(?:[?#].*)?$/);
      const rawTitle = source.title || url || "未知来源";
      const legacyOffline = !internalArticle && rawTitle.startsWith("站内知识：");
      const offline = rawTitle.startsWith("离线语料：") || legacyOffline;
      const displayTitle = rawTitle.replace(/^(?:站内知识|离线语料)：\s*/, "");
      const sourceKind = internalArticle ? "站内文章" : offline ? "离线语料 · 原始页" : "外部来源";
      const link = internalArticle
        ? '<button class="source-link" data-source-article-id="' + internalArticle[1] + '" type="button">' +
          root.util.escapeHtml(displayTitle || "站内文章") + '</button>'
        : url
          ? '<a href="' + root.util.escapeHtml(url) + '" target="_blank" rel="noopener noreferrer">' + root.util.escapeHtml(displayTitle || url) + '</a>'
          : '<b>' + root.util.escapeHtml(displayTitle) + '</b>';
      return '<div class="source-card"><div class="source-card-meta"><small>SRC-' + (index + 1) + ' · DB #' + source.id + '</small>' +
        '<span class="source-kind">' + sourceKind + '</span></div>' +
        link +
        '<small title="' + root.util.escapeHtml(source.contentHash || "") + '">' + root.util.escapeHtml(shortHash(source.contentHash)) + '</small></div>';
    }).join("") : '<div class="empty-copy">没有来源</div>';
  }

  function shortHash(value) {
    const text = String(value || "");
    return text.length > 16 ? text.slice(0, 12) + "…" : text;
  }

  function renderEvidence(evidence, citations, sources) {
    const bindings = new Map();
    const sourceIndexes = new Map(sources.map(function (source, index) { return [source.id, index + 1]; }));
    citations.forEach(function (citation) {
      const list = bindings.get(citation.evidenceId) || [];
      list.push("[" + citation.claimId + "]");
      bindings.set(citation.evidenceId, list);
    });
    const box = document.getElementById("evidenceList");
    box.innerHTML = evidence.length ? evidence.map(function (item, index) {
      const claims = bindings.get(item.id) || [];
      const sourceIndex = sourceIndexes.get(item.sourceId) || "?";
      return '<div class="evidence-card"><small>EV-' + (index + 1) + ' · DB #' + item.id + ' · SRC-' + sourceIndex +
        ' · SCORE ' + Number(item.score || 0).toFixed(2) + '</small><p>' + root.util.escapeHtml(item.content || "") + '</p>' +
        '<small>' + root.util.escapeHtml(item.locator || "") + (claims.length ? ' · 支撑 ' + root.util.escapeHtml(claims.join(", ")) : '') + '</small></div>';
    }).join("") : '<div class="empty-copy">没有 Evidence</div>';
  }

  function renderCitations(citations, evidence, sources) {
    const box = document.getElementById("citationList");
    const evidenceById = new Map(evidence.map(function (item, index) {
      return [item.id, { item: item, index: index + 1 }];
    }));
    const sourceById = new Map(sources.map(function (item, index) {
      return [item.id, { item: item, index: index + 1 }];
    }));
    box.innerHTML = citations.length ? citations.map(function (citation) {
      const evidenceEntry = evidenceById.get(citation.evidenceId);
      const sourceEntry = evidenceEntry && sourceById.get(evidenceEntry.item.sourceId);
      const evidenceRef = evidenceEntry ? "ev-" + evidenceEntry.index : "ev-?";
      const sourceRef = sourceEntry ? "src-" + sourceEntry.index : "src-?";
      const sourceTitle = sourceEntry
        ? String(sourceEntry.item.title || "未知来源").replace(/^(?:站内知识|离线语料)：\s*/, "")
        : "来源记录缺失";
      return '<div class="citation-card"><div class="citation-chain">' +
        '<span class="citation-node claim-node">[' + root.util.escapeHtml(citation.claimId) + ']</span>' +
        '<span class="citation-arrow">引用 →</span>' +
        '<span class="citation-node evidence-node">[' + evidenceRef + ']</span>' +
        '<span class="citation-arrow">来自 →</span>' +
        '<span class="citation-node source-node">[' + sourceRef + ']</span></div>' +
        '<small>支持度 ' + Number(citation.supportScore || 0).toFixed(2) + ' · ' + root.util.escapeHtml(sourceTitle) + '</small></div>';
    }).join("") : '<div class="empty-copy">没有引用关系</div>';
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
    document.getElementById("sourceList").addEventListener("click", function (event) {
      const link = event.target.closest("[data-source-article-id]");
      if (link) root.community.openArticle(link.dataset.sourceArticleId);
    });
    document.getElementById("reportContent").addEventListener("input", function (event) {
      document.getElementById("reportPreview").innerHTML = root.util.renderMarkdown(event.target.value);
    });
    document.getElementById("researchProvider").addEventListener("change", function (event) {
      const hint = document.getElementById("providerHint");
      if (event.target.value === "openai-tools") {
        hint.textContent = "在线模式会调用模型与工具并产生真实 API 费用；API Key 仅保存在 Agent 服务端。";
      } else {
        hint.textContent = "离线模式不消耗模型额度，使用固定语料验证完整工作流。";
      }
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
