/** The config */
const MAX_BATCH = 500; // The maximum number of messages accepted in a single request.
const DEFAULT_MAX_RECORDS = 200; // Fallback record limit used when MAX_RECORDS is not set.
const INSERT_CHUNK = 100; // Statements per D1 batch call.

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

// ------------------------------------------------------------------ D1 store

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

// Columns are named from_addr / received_at etc. because `from` is a SQL
// keyword; map back to the JSON field names the clients already expect.
function rowToRecord(row) {
  return {
    id: row.id,
    ts: row.ts,
    receivedAt: row.received_at,
    from: row.from_addr,
    content: row.content,
    device: row.device,
    deviceTs: row.device_ts,
    clientId: row.client_id,
  };
}

const SELECT_COLUMNS =
  "id, ts, received_at, from_addr, content, device, device_ts, client_id";

const INSERT_SQL = `INSERT OR IGNORE INTO messages
  (id, ts, received_at, from_addr, content, device, device_ts, client_id)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?)`;

/**
 * Kept in sync with schema.sql. The Worker applies it itself because a
 * dashboard deploy never runs `wrangler d1 execute`, so a fresh database would
 * otherwise answer every request with "no such table: messages".
 */
const SCHEMA_STATEMENTS = [
  `CREATE TABLE IF NOT EXISTS messages (
     id          TEXT    PRIMARY KEY,
     ts          INTEGER NOT NULL,
     received_at TEXT    NOT NULL,
     from_addr   TEXT    NOT NULL DEFAULT '',
     content     TEXT    NOT NULL DEFAULT '',
     device      TEXT    NOT NULL DEFAULT '',
     device_ts   INTEGER,
     client_id   TEXT
   )`,
  `CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages (ts DESC, id DESC)`,
  `CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_client_id
     ON messages (client_id) WHERE client_id IS NOT NULL`,
];

// Cached per isolate: the DDL is idempotent, but there is no reason to re-run it
// on every request. Reset on failure so a transient error can be retried.
let schemaReady = null;

function ensureSchema(db) {
  if (!schemaReady) {
    schemaReady = (async () => {
      await db.batch(SCHEMA_STATEMENTS.map((sql) => db.prepare(sql)));
    })().catch((err) => {
      schemaReady = null;
      throw err;
    });
  }
  return schemaReady;
}

class SmsStore {
  constructor(db, maxRecords) {
    this.db = db;
    this.max = normalizeMax(maxRecords);
  }

  /**
   * Writes a batch of normalized messages.
   *
   * Deduplication is left to the unique index on client_id: a repeat insert is
   * silently ignored by `INSERT OR IGNORE`, and `meta.changes === 0` is what
   * tells us it was a duplicate. That removes the read-then-write race the old
   * KV version had.
   */
  async appendMany(items) {
    const prepared = items.map((item) => {
      const id = buildId(item.ts);
      return {
        id,
        clientId: item.clientId,
        stmt: this.db
          .prepare(INSERT_SQL)
          .bind(
            id,
            item.ts,
            new Date(item.ts).toISOString(),
            item.from,
            item.content,
            item.device,
            item.deviceTs,
            item.clientId,
          ),
      };
    });

    const inserted = new Array(prepared.length).fill(false);

    for (let i = 0; i < prepared.length; i += INSERT_CHUNK) {
      const chunk = prepared.slice(i, i + INSERT_CHUNK);
      const results = await this.db.batch(chunk.map((p) => p.stmt));
      results.forEach((res, j) => {
        inserted[i + j] = (res.meta?.changes ?? 0) > 0;
      });
    }

    // For the rows that were ignored, look up which record already owns the
    // client_id so the response can echo its id, as the KV version did.
    const dupIds = new Map();
    const dupClientIds = prepared.filter((p, i) => !inserted[i] && p.clientId).map((p) => p.clientId);
    if (dupClientIds.length > 0) {
      const placeholders = dupClientIds.map(() => "?").join(",");
      const found = await this.db
        .prepare(`SELECT client_id, id FROM messages WHERE client_id IN (${placeholders})`)
        .bind(...dupClientIds)
        .all();
      for (const row of found.results ?? []) dupIds.set(row.client_id, row.id);
    }

    const evicted = await this.trim();

    const results = prepared.map((p, i) =>
      inserted[i]
        ? { ok: true, id: p.id, duplicate: false }
        : { ok: true, id: dupIds.get(p.clientId) ?? p.id, duplicate: true },
    );

    const duplicates = results.filter((r) => r.duplicate).length;
    return { results, accepted: results.length - duplicates, duplicates, evicted };
  }

  /**
   * Enforces MAX_RECORDS. Everything past the newest `max` rows is deleted in
   * one statement, so no row can survive outside the window.
   *
   * Ordering is by rowid rather than id: id carries a random suffix, so within
   * one millisecond (a whole batch shares a ts) it would pick an arbitrary
   * subset. rowid is the insertion sequence, which makes both the window and
   * the listing deterministic. It stays monotonic because this trim only ever
   * removes the oldest rows, so the highest rowid is never freed for reuse.
   */
  async trim() {
    const res = await this.db
      .prepare(
        `DELETE FROM messages WHERE id IN (
           SELECT id FROM messages ORDER BY ts DESC, rowid DESC LIMIT -1 OFFSET ?
         )`,
      )
      .bind(this.max)
      .run();
    return res.meta?.changes ?? 0;
  }

  async list({ limit = 100, since = 0 } = {}) {
    const res = await this.db
      .prepare(
        `SELECT ${SELECT_COLUMNS} FROM messages
         WHERE ts > ?
         ORDER BY ts DESC, rowid DESC
         LIMIT ?`,
      )
      .bind(since, limit)
      .all();
    return (res.results ?? []).map(rowToRecord);
  }

  async count() {
    const res = await this.db.prepare("SELECT COUNT(*) AS n FROM messages").first();
    return res?.n ?? 0;
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

async function getStore(env) {
  if (!env.DB) throw new Error("Missing D1 binding, check wrangler.toml");
  await ensureSchema(env.DB);
  return new SmsStore(env.DB, normalizeMax(env.MAX_RECORDS));
}

function normalizeOne(raw) {
  if (!raw || typeof raw !== "object") return null;
  const from = String(raw.from ?? raw.address ?? raw.sender ?? "").trim();
  const content = String(raw.content ?? raw.body ?? raw.message ?? "").trim();
  if (!from && !content) return null;

  const deviceTs = Number(raw.deviceTs ?? raw.date ?? raw.timestamp);
  const rawClientId = raw.clientId ?? raw.id ?? "";
  const clientId = String(rawClientId).trim();

  return {
    from: from || "(Unknown)",
    content: content.slice(0, 8000),
    device: String(raw.device ?? "").slice(0, 128),
    deviceTs: Number.isFinite(deviceTs) && deviceTs > 0 ? deviceTs : null,
    // Empty means "no idempotency key" and must become NULL, otherwise every
    // such message would collide on the unique index and be dropped.
    clientId: clientId ? clientId.slice(0, 256) : null,
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

  const store = await getStore(env);

  // Keep the input order so the per-item results line up with what was sent,
  // but only hand valid rows to the store.
  const results = new Array(rawList.length);
  const valid = [];
  const validIndex = [];

  rawList.forEach((raw, i) => {
    const item = normalizeOne(raw);
    if (!item) {
      results[i] = { ok: false, error: "Missing sender or content" };
    } else {
      valid.push(item);
      validIndex.push(i);
    }
  });

  let accepted = 0;
  let duplicates = 0;
  let evictedTotal = 0;

  if (valid.length > 0) {
    try {
      const out = await store.appendMany(valid);
      out.results.forEach((r, j) => {
        results[validIndex[j]] = r;
      });
      accepted = out.accepted;
      duplicates = out.duplicates;
      evictedTotal = out.evicted;
    } catch (err) {
      for (const idx of validIndex) results[idx] = { ok: false, error: err.message };
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

  const store = await getStore(env);
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
        const store = await getStore(env);
        return json({
          ok: true,
          service: "sms-forwarder",
          db: true,
          stored: await store.count(),
          maxRecords: store.max,
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
