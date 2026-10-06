/** The config */
const MAX_BATCH = 500; // The maximum number of messages accepted in a single request.
const DEFAULT_MAX_RECORDS = 200; // Fallback record limit used when MAX_RECORDS is not set.
const LIST_PAGE = 1000; // Page size for KV list operations.

const INDEX_KEY = "sms:index";
const REC_PREFIX = "sms:rec:";
const CID_PREFIX = "sms:cid:";
const DEDUP_TTL = 60 * 60 * 24 * 7;

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type,X-Admin-Password,AccessToken,Authorization",
  "Access-Control-Max-Age": "86400",
};

// ------------------------------------------------------------------ Auth

function safeEqual(a, b) {
  const x = new TextEncoder().encode(String(a ?? ""));
  const y = new TextEncoder().encode(String(b ?? ""));
  if (x.length !== y.length) return false;
  let diff = 0;
  for (let i = 0; i < x.length; i += 1) diff |= x[i] ^ y[i];
  return diff === 0;
}

function extractAdminCredential(request) {
  const header = request.headers.get("X-Admin-Password");
  if (header) return header;

  const accessToken = request.headers.get("AccessToken");
  if (accessToken) return accessToken;

  const auth = request.headers.get("Authorization") || "";
  if (/^Bearer\s+/i.test(auth)) return auth.replace(/^Bearer\s+/i, "").trim();

  return "";
}
function checkAdmin(request, env) {
  const expected = env.ADMIN;
  if (!expected) {
    return { ok: false, status: 500, reason: "Server misconfigured: ADMIN is not set" };
  }
  const provided = extractAdminCredential(request);
  if (!provided) {
    return { ok: false, status: 401, reason: "Unauthorized: no password provided" };
  }
  if (!safeEqual(provided, expected)) {
    return { ok: false, status: 403, reason: "Forbidden: wrong password" };
  }
  return { ok: true };
}

// ------------------------------------------------------------------ KV store

function pad(n, len) {
  return String(n).padStart(len, "0");
}

function randomSuffix() {
  const bytes = new Uint8Array(3);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

function buildId(ts) {
  return `${pad(ts, 18)}-${randomSuffix()}`;
}

function normalizeMax(value) {
  const n = Number.parseInt(value ?? "", 10);
  if (!Number.isFinite(n) || n <= 0) return DEFAULT_MAX_RECORDS;
  return Math.min(n, 5000);
}

class SmsStore {
  constructor(kv, maxRecords) {
    this.kv = kv;
    this.max = normalizeMax(maxRecords);
  }

  async readIndex() {
    const raw = await this.kv.get(INDEX_KEY, "json");
    if (!Array.isArray(raw)) return [];
    return raw.filter((id) => typeof id === "string" && id.length > 0);
  }

  async append(input) {
    const ts = Number.isFinite(input.ts) ? input.ts : Date.now();
    const clientId = typeof input.clientId === "string" ? input.clientId.trim() : "";

    if (clientId) {
      const existingId = await this.kv.get(CID_PREFIX + clientId);
      if (existingId) {
        const existing = await this.kv.get(REC_PREFIX + existingId, "json");
        if (existing) return { record: existing, duplicate: true, evicted: [] };
      }
    }

    const record = {
      id: buildId(ts),
      ts,
      receivedAt: new Date(ts).toISOString(),
      from: String(input.from ?? "").slice(0, 128),
      content: String(input.content ?? "").slice(0, 8000),
      device: String(input.device ?? "").slice(0, 128),
      deviceTs: Number.isFinite(input.deviceTs) ? input.deviceTs : null,
      clientId: clientId || null,
    };

    await this.kv.put(REC_PREFIX + record.id, JSON.stringify(record));

    if (clientId) {
      await this.kv.put(CID_PREFIX + clientId, record.id, { expirationTtl: DEDUP_TTL });
    }

    const evicted = await this.#indexAppend(record.id);

    return { record, duplicate: false, evicted };
  }

  async #indexAppend(id) {
    let evicted = [];

    for (let attempt = 0; attempt < 5; attempt += 1) {
      const index = await this.readIndex();
      if (!index.includes(id)) {
        index.push(id);
        evicted = [];
        while (index.length > this.max) {
          const oldest = index.shift();
          if (oldest) evicted.push(oldest);
        }
        await this.kv.put(INDEX_KEY, JSON.stringify(index));
        if (evicted.length > 0) {
          await Promise.all(evicted.map((oldId) => this.kv.delete(REC_PREFIX + oldId)));
        }
      }

      const check = await this.readIndex();
      if (check.includes(id)) return evicted;

      await new Promise((r) => setTimeout(r, 40 * (attempt + 1)));
    }

    return evicted;
  }

  async list({ limit = 100, since = 0 } = {}) {
    let index = await this.readIndex();
    if (index.length === 0) index = await this.rebuildIndex();

    const ids = index.slice().reverse();
    const picked = [];
    for (const id of ids) {
      const rec = await this.kv.get(REC_PREFIX + id, "json");
      if (!rec) continue;
      if (since && Number(rec.ts) <= since) continue;
      picked.push(rec);
      if (picked.length >= limit) break;
    }
    return picked;
  }

  async rebuildIndex() {
    const ids = [];
    let cursor;
    do {
      const page = await this.kv.list({ prefix: REC_PREFIX, cursor, limit: LIST_PAGE });
      for (const key of page.keys) ids.push(key.name.slice(REC_PREFIX.length));
      cursor = page.list_complete ? undefined : page.cursor;
    } while (cursor);

    ids.sort(); // The id prefix is a zero-padded timestamp, so lexicographic order equals time order.
    const trimmed = ids.slice(-this.max);
    await this.kv.put(INDEX_KEY, JSON.stringify(trimmed));
    return trimmed;
  }
}

// ------------------------------------------------------------------ Web page

const PAGE_HTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>SMS Forwarder</title>
<meta name="theme-color" content="#1f6feb">
<style>
  :root { color-scheme: light dark; }
  body { font: 14px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; margin: 0; padding: 12px; }
  header { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
  input, button { font: inherit; padding: 6px 8px; }
  input[type=password] { flex: 1 1 180px; min-width: 120px; }
  #acc { width: 100%; box-sizing: border-box; margin-bottom: 8px; font-size: 12px; }
  #status { margin-bottom: 12px; font-size: 12px; opacity: .7; }
  #list { display: flex; flex-direction: column; gap: 8px; }
  .item { border: 1px solid rgba(128,128,128,.4); border-radius: 6px; padding: 8px 10px; }
  .meta { display: flex; justify-content: space-between; gap: 8px; font-size: 12px; opacity: .7; margin-bottom: 4px; }
  .from { font-weight: 600; opacity: .9; }
  .body { white-space: pre-wrap; word-break: break-word; }
  .empty { padding: 16px; text-align: center; opacity: .6; }
</style>
</head>
<body>
<header>
  <input id="pwd" type="password" placeholder="Password" autocomplete="current-password">
  <button id="refresh">Refresh</button>
  <label><input id="auto" type="checkbox"> Auto-refresh</label>
</header>
<input id="acc" placeholder="access_..." autocomplete="off">
<div id="status">Unloaded</div>
<div id="list"></div>

<script>
(function () {
  var pwd = document.getElementById('pwd');
  var acc = document.getElementById('acc');
  var status = document.getElementById('status');
  var list = document.getElementById('list');
  var auto = document.getElementById('auto');
  var timer = null;
  var KEY = 'sms_admin_pwd';
  var ACC_KEY = 'sms_access_field';

  pwd.value = localStorage.getItem(KEY) || '';

  function detectAccessField() {
    var found = '';
    try {
      new URLSearchParams(location.search).forEach(function (v, k) {
        if (!found && k.indexOf('access_') === 0) found = k;
      });
    } catch (e) { /* Old browsers do not have URLSearchParams, ignore */ }

    if (found) {
      try { localStorage.setItem(ACC_KEY, found); } catch (e) {}
      document.cookie = found + '=1; path=/; max-age=31536000; SameSite=Lax';
      return found;
    }
    try { return localStorage.getItem(ACC_KEY) || ''; } catch (e) { return ''; }
  }

  acc.value = detectAccessField();

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function fmt(ts) {
    if (!ts) return '-';
    return new Date(ts).toLocaleString();
  }

  function render(items) {
    if (!items.length) {
      list.innerHTML = '<div class="empty">No messages</div>';
      return;
    }
    list.innerHTML = items.map(function (m) {
      return '<div class="item">' +
        '<div class="meta"><span class="from">' + esc(m.from || '(Unknown)') + '</span>' +
        '<span>' + esc(fmt(m.ts)) + (m.device ? ' · ' + esc(m.device) : '') + '</span></div>' +
        '<div class="body">' + esc(m.content) + '</div>' +
        '</div>';
    }).join('');
  }

  async function load() {
    var p = pwd.value.trim();
    var field = acc.value.trim();
    if (!p) {
      status.textContent = 'Please enter the password';
      return;
    }
    status.textContent = 'Loading...';
    var url = '/api/sms?limit=200';
    var headers = { 'X-Admin-Password': p };
    if (field) {
      url += '&' + encodeURIComponent(field) + '=1';
      headers[field] = '1';
    }

    try {
      var res = await fetch(url, { headers: headers });
      var raw = await res.text();
      var data = {};
      try { data = JSON.parse(raw); } catch (e) { /* Not JSON */ }
      if (!res.ok) {
        status.textContent = 'Failed (' + res.status + '): ' + (data.error || res.statusText);
        if (res.status === 401 || res.status === 403) list.innerHTML = '';
        return;
      }
      localStorage.setItem(KEY, p);
      if (field) { try { localStorage.setItem(ACC_KEY, field); } catch (e) {} }
      render(data.messages || []);
      status.textContent = 'Total ' + (data.count || 0) + ' messages / max ' + (data.max || '-') +
        ' · updated at ' + new Date().toLocaleTimeString();
    } catch (e) {
      status.textContent = 'Failed: ' + e.message;
    }
  }

  document.getElementById('refresh').addEventListener('click', load);
  pwd.addEventListener('keydown', function (e) { if (e.key === 'Enter') load(); });
  acc.addEventListener('keydown', function (e) { if (e.key === 'Enter') load(); });
  acc.addEventListener('change', function () {
    var v = acc.value.trim();
    try {
      if (v) localStorage.setItem(ACC_KEY, v);
      else localStorage.removeItem(ACC_KEY);
    } catch (e) {}
  });
  auto.addEventListener('change', function () {
    if (timer) { clearInterval(timer); timer = null; }
    if (auto.checked) { load(); timer = setInterval(load, 15000); }
  });

  if (pwd.value) load();
})();
</script>
</body>
</html>
`;

// ------------------------------------------------------------------ Routing

function json(data, status = 200) {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...CORS_HEADERS },
  });
}

function text(body, status = 200, contentType = "text/plain; charset=utf-8") {
  return new Response(body, { status, headers: { "Content-Type": contentType, ...CORS_HEADERS } });
}

function getStore(env) {
  if (!env.kv) throw new Error("Missing KV binding, check wrangler.toml");
  return new SmsStore(env.kv, normalizeMax(env.MAX_RECORDS));
}

function normalizeOne(raw) {
  if (!raw || typeof raw !== "object") return null;
  const from = String(raw.from ?? raw.address ?? raw.sender ?? "").trim();
  const content = String(raw.content ?? raw.body ?? raw.message ?? "").trim();
  if (!from && !content) return null;

  const deviceTs = Number(raw.deviceTs ?? raw.date ?? raw.timestamp);
  return {
    from: from || "(Unknown)",
    content,
    device: raw.device ?? "",
    deviceTs: Number.isFinite(deviceTs) && deviceTs > 0 ? deviceTs : null,
    clientId: raw.clientId ?? raw.id ?? "",
    ts: Date.now(),
  };
}

async function handleIngest(request, env) {
  let body;
  try {
    body = await request.json();
  } catch {
    return json({ ok: false, error: "Request body is not valid JSON" }, 400);
  }

  const rawList = Array.isArray(body) ? body : Array.isArray(body?.messages) ? body.messages : [body];
  if (rawList.length === 0) return json({ ok: false, error: "No records to write" }, 400);
  if (rawList.length > MAX_BATCH) {
    return json({ ok: false, error: `At most ${MAX_BATCH} messages per request, got ${rawList.length}` }, 413);
  }

  const store = getStore(env);
  const results = [];
  let accepted = 0;
  let duplicates = 0;
  let evictedTotal = 0;

  for (const raw of rawList) {
    const item = normalizeOne(raw);
    if (!item) {
      results.push({ ok: false, error: "Missing sender or content" });
      continue;
    }
    try {
      const { record, duplicate, evicted } = await store.append(item);
      if (duplicate) duplicates += 1;
      else {
        accepted += 1;
        evictedTotal += evicted?.length || 0;
      }
      results.push({ ok: true, id: record.id, duplicate: Boolean(duplicate) });
    } catch (err) {
      results.push({ ok: false, error: err.message });
    }
  }

  const failed = results.filter((r) => !r.ok).length;
  const payload = {
    ok: failed === 0,
    accepted,
    duplicates,
    failed,
    evicted: evictedTotal,
    results: results.length === 1 ? results[0] : results,
  };

  return json(payload, failed === 0 ? 200 : failed === results.length ? 400 : 207);
}

async function handleList(url, env) {
  const limitRaw = Number.parseInt(url.searchParams.get("limit") ?? "100", 10);
  const limit = Number.isFinite(limitRaw) ? Math.min(Math.max(limitRaw, 1), 1000) : 100;
  const since = Number.parseInt(url.searchParams.get("since") ?? "0", 10) || 0;

  const store = getStore(env);
  const messages = await store.list({ limit, since });
  return json({ ok: true, count: messages.length, max: store.max, messages });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    try {
      if (path === "/" || path === "/index.html") {
        if (request.method !== "GET") return text("Method Not Allowed", 405);
        return text(PAGE_HTML, 200, "text/html; charset=utf-8");
      }
      if (!path.startsWith("/api/")) {
        return json({ ok: false, error: `Unknown route ${request.method} ${path}` }, 404);
      }
      if (path === "/api/health") {
        if (request.method !== "GET") return json({ ok: false, error: "Method Not Allowed" }, 405);
        return json({
          ok: true,
          service: "sms-forwarder",
          kv: Boolean(env.kv),
          maxRecords: normalizeMax(env.MAX_RECORDS),
        });
      }
      const auth = checkAdmin(request, env);
      if (!auth.ok) {
        return json({ ok: false, error: auth.reason }, auth.status);
      }

      if (path === "/api/sms") {
        if (request.method === "POST") return await handleIngest(request, env);
        if (request.method === "GET") return await handleList(url, env);
        return json({ ok: false, error: "Method Not Allowed" }, 405);
      }

      return json({ ok: false, error: `Unknown route ${request.method} ${path}` }, 404);
    } catch (err) {
      return json({ ok: false, error: err.message || String(err) }, 500);
    }
  },
};
