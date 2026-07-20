(function (global) {
  "use strict";

  const root = global.XP = global.XP || {};
  const storage = global.localStorage;
  const state = {
    baseUrl: storage.getItem("xplanet.apiBase") || "http://localhost:8080",
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
    const response = await fetch(state.baseUrl + path, config);
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
    const response = await fetch(state.baseUrl + path, {
      method: "GET",
      headers: headers,
      cache: "no-store",
      signal: config.signal
    });
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
    setToken: setToken,
    authHeaders: authHeaders,
    request: request,
    stream: stream
  };
})(window);
