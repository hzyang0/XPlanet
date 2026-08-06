(function (global) {
  "use strict";

  const root = global.XP = global.XP || {};
  const storage = global.localStorage;
  const state = {
    baseUrl: normalizeBase(storage.getItem("xplanet.apiBase") || ""),
    token: storage.getItem("xplanet.token") || ""
  };

  class ApiError extends Error {
    constructor(message, code, status) {
      super(message);
      this.name = "ApiError";
      this.code = code;
      this.status = status;
    }
  }

  function normalizeBase(value) {
    return String(value || "").trim().replace(/\/+$/, "");
  }

  function setBaseUrl(value) {
    const normalized = normalizeBase(value);
    if (!/^https?:\/\//i.test(normalized)) {
      throw new ApiError("Gateway 地址必须以 http:// 或 https:// 开头", "INVALID_BASE", 0);
    }
    state.baseUrl = normalized;
    storage.setItem("xplanet.apiBase", normalized);
  }

  async function probeGateway(baseUrl) {
    const controller = new AbortController();
    const timer = global.setTimeout(function () { controller.abort(); }, 1600);
    try {
      const response = await fetch(normalizeBase(baseUrl) + "/api/article/list?pageNum=1&pageSize=1", {
        headers: { Accept: "application/json" },
        cache: "no-store",
        signal: controller.signal
      });
      if (!response.ok) return false;
      const payload = await response.json();
      return payload && payload.code === 0 && payload.data && Array.isArray(payload.data.records);
    } catch (_) {
      return false;
    } finally {
      global.clearTimeout(timer);
    }
  }

  async function discoverBaseUrl() {
    const protocol = global.location.protocol === "https:" ? "https:" : "http:";
    const hostname = global.location.hostname || "localhost";
    const candidates = [
      state.baseUrl,
      global.XP_GATEWAY_BASE_URL,
      protocol + "//" + hostname + ":8080",
      protocol + "//" + hostname + ":18080",
      "http://localhost:8080",
      "http://localhost:18080",
      "http://127.0.0.1:8080",
      "http://127.0.0.1:18080"
    ].map(normalizeBase).filter(function (value, index, values) {
      return value && values.indexOf(value) === index;
    });
    const results = await Promise.all(candidates.map(probeGateway));
    const selected = candidates[results.indexOf(true)];
    if (selected) {
      setBaseUrl(selected);
      return { connected: true, baseUrl: selected };
    }
    state.baseUrl = candidates[0] || protocol + "//" + hostname + ":8080";
    return { connected: false, baseUrl: state.baseUrl };
  }

  function setToken(value) {
    state.token = String(value || "");
    if (state.token) storage.setItem("xplanet.token", state.token);
    else storage.removeItem("xplanet.token");
  }

  function authHeaders(headers) {
    const result = Object.assign({}, headers || {});
    if (state.token) result.Authorization = "Bearer " + state.token;
    return result;
  }

  async function request(path, options) {
    const config = Object.assign({}, options || {});
    config.headers = authHeaders(config.headers);
    if (config.body && typeof config.body !== "string") {
      config.headers["Content-Type"] = "application/json";
      config.body = JSON.stringify(config.body);
    }
    if (!state.baseUrl) throw new ApiError("尚未找到可用的 Gateway", "GATEWAY_UNAVAILABLE", 0);
    let response;
    try {
      response = await fetch(state.baseUrl + path, config);
    } catch (_) {
      throw new ApiError("无法连接 Gateway " + state.baseUrl + "，请确认服务已启动或修改地址", "GATEWAY_UNAVAILABLE", 0);
    }
    const contentType = response.headers.get("content-type") || "";
    let payload = null;
    if (contentType.includes("application/json")) {
      payload = await response.json();
    } else {
      const text = await response.text();
      payload = text ? { msg: text } : null;
    }
    if (!response.ok) {
      throw new ApiError(payload && payload.msg ? payload.msg : "HTTP " + response.status,
        payload && payload.code, response.status);
    }
    if (payload && typeof payload.code !== "undefined" && payload.code !== 0) {
      throw new ApiError(payload.msg || "请求失败", payload.code, response.status);
    }
    return payload && Object.prototype.hasOwnProperty.call(payload, "data") ? payload.data : payload;
  }

  function parseSseBlock(block) {
    const parsed = { id: "", event: "message", data: "" };
    const dataLines = [];
    block.split(/\r?\n/).forEach(function (line) {
      if (!line || line.startsWith(":")) return;
      const separator = line.indexOf(":");
      const field = separator < 0 ? line : line.slice(0, separator);
      let value = separator < 0 ? "" : line.slice(separator + 1);
      if (value.startsWith(" ")) value = value.slice(1);
      if (field === "id") parsed.id = value;
      else if (field === "event") parsed.event = value;
      else if (field === "data") dataLines.push(value);
    });
    parsed.data = dataLines.join("\n");
    return parsed;
  }

  async function stream(path, options) {
    const config = options || {};
    const headers = authHeaders({ Accept: "text/event-stream" });
    if (config.lastEventId) headers["Last-Event-ID"] = config.lastEventId;
    let response;
    try {
      response = await fetch(state.baseUrl + path, {
        method: "GET",
        headers: headers,
        cache: "no-store",
        signal: config.signal
      });
    } catch (error) {
      if (error.name === "AbortError") throw error;
      throw new ApiError("无法连接 Gateway " + state.baseUrl, "GATEWAY_UNAVAILABLE", 0);
    }
    if (!response.ok) {
      let message = "SSE 连接失败：HTTP " + response.status;
      try {
        const payload = await response.json();
        if (payload && payload.msg) message = payload.msg;
      } catch (_) { /* response is not JSON */ }
      throw new ApiError(message, "SSE_FAILED", response.status);
    }
    if (!response.body) throw new ApiError("当前浏览器不支持流式响应", "SSE_UNSUPPORTED", 0);

    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";
    while (true) {
      const result = await reader.read();
      if (result.done) break;
      buffer += decoder.decode(result.value, { stream: true }).replace(/\r\n/g, "\n");
      let boundary = buffer.indexOf("\n\n");
      while (boundary >= 0) {
        const block = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        if (block.trim() && config.onEvent) config.onEvent(parseSseBlock(block));
        boundary = buffer.indexOf("\n\n");
      }
    }
    buffer += decoder.decode();
    if (buffer.trim() && config.onEvent) config.onEvent(parseSseBlock(buffer));
  }

  root.api = {
    ApiError: ApiError,
    state: state,
    setBaseUrl: setBaseUrl,
    discoverBaseUrl: discoverBaseUrl,
    setToken: setToken,
    authHeaders: authHeaders,
    request: request,
    stream: stream
  };
})(window);
