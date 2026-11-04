// A fake Zeitwide API for `next build`, local development and Playwright. It speaks the contract
// in api/openapi.yaml with fixed data, so tests are deterministic. This is test infrastructure:
// the date arithmetic here stands in for the Java engine and never ships in the web app.
//
//   node e2e/mock-api/server.mjs          (port 8090, or MOCK_API_PORT)

import { createHash, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import {
  CALENDARS,
  CHANGES,
  NOW,
  RELEASE,
  RELEASE_AS_OF,
  TODAY,
  hashLike,
  initialAdminState,
  recordedFrom,
  sourcesFor,
} from "./data.mjs";

const PORT = Number(process.env.MOCK_API_PORT ?? 8090);
// Written by `zeitwide-pipeline site-files` from e2e/mock-api/write-release.mjs.
const SITE_FILES =
  process.env.MOCK_SITE_FILES ?? join(dirname(fileURLToPath(import.meta.url)), "..", ".site-files");
const DAY_MS = 86_400_000;
let state = freshState();

function freshState() {
  return { accounts: new Map(), admin: initialAdminState(), receipts: new Map() };
}

// ---- dates (fake-engine only) ----
const toDate = (iso) => new Date(`${iso}T00:00:00Z`);
const toIso = (date) => date.toISOString().slice(0, 10);
const addDays = (iso, days) => toIso(new Date(toDate(iso).getTime() + days * DAY_MS));
const weekday = (iso) =>
  ["sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"][
    toDate(iso).getUTCDay()
  ];

function covered(calendar, iso) {
  if (calendar.years === null) return true;
  const year = Number(iso.slice(0, 4));
  return year >= calendar.years[0] && year <= calendar.years[1];
}

function calendarDay(calendar, iso, asOf) {
  const reason = calendar.holidays[iso];
  if (reason !== undefined && (asOf === undefined || asOf >= recordedFrom(iso))) {
    return {
      calendar_id: calendar.id,
      is_open: false,
      decided_by: "entry",
      reason,
      basis: "published",
      revision: 12,
      recorded_from: recordedFrom(iso),
      sources: sourcesFor(calendar, iso),
    };
  }
  return {
    calendar_id: calendar.id,
    is_open: !calendar.weekend.includes(weekday(iso)),
    decided_by: "weekend_rule",
    sources: [],
  };
}

function neighbour(calendar, iso, step, asOf) {
  let current = iso;
  for (let i = 0; i < 30; i++) {
    current = addDays(current, step);
    if (!covered(calendar, current)) return undefined;
    if (calendarDay(calendar, current, asOf).is_open) return current;
  }
  return undefined;
}

// ---- envelope helpers ----
const revisions = (ids) => Object.fromEntries(ids.map((id) => [id, 12]));

function receipt(request, answer, ids) {
  const payload = {
    request,
    answer,
    dataset_release: RELEASE,
    calendar_revisions: revisions(ids),
    issued_at: NOW,
  };
  const payloadHash = createHash("sha256").update(JSON.stringify(payload)).digest("hex");
  const id = `rcpt_${payloadHash.slice(0, 32)}`;
  const signature = createHash("sha512")
    .update(`sig:${payloadHash}`)
    .digest("base64url")
    .slice(0, 86);
  state.receipts.set(id, payloadHash);
  return { id, payload, payload_hash: payloadHash, key_id: "zw-2026-10", signature };
}

function answered(body, request, ids, asOf) {
  const at = asOf !== undefined && asOf < RELEASE_AS_OF ? asOf : RELEASE_AS_OF;
  const full = { ...body, as_of: at, dataset_release: RELEASE, calendar_revisions: revisions(ids) };
  return { ...full, receipt: receipt(request, body, ids) };
}

function problem(res, status, code, detail) {
  send(
    res,
    status,
    { type: "about:blank", title: detail, status, code, detail },
    "application/problem+json",
  );
}

function send(res, status, body, type = "application/json") {
  res.writeHead(status, {
    "content-type": type,
    "zeitwide-dataset-release": RELEASE,
  });
  res.end(body === undefined ? undefined : JSON.stringify(body));
}

// ---- identity ----
function claims(req) {
  const header = req.headers.authorization ?? "";
  if (!header.startsWith("Bearer ")) return null;
  const token = header.slice(7);
  if (token.startsWith("zw_live_")) return { sub: `key:${token.slice(0, 16)}`, roles: [] };
  const payload = token.split(".")[1];
  if (payload === undefined) return null;
  try {
    const parsed = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
    return { sub: parsed.sub, roles: parsed.roles ?? [] };
  } catch {
    return null;
  }
}

function account(sub) {
  if (!state.accounts.has(sub)) {
    state.accounts.set(sub, { keys: [], webhooks: [], rules: [], idempotency: new Set() });
  }
  return state.accounts.get(sub);
}

function calendarOr404(res, id) {
  const calendar = CALENDARS.find((c) => c.id === id);
  if (calendar === undefined) problem(res, 404, "unknown_calendar", `No calendar ${id} exists`);
  return calendar;
}

function summary(calendar) {
  const coverage =
    calendar.years === null
      ? { type: "unlimited" }
      : { type: "years", from: `${calendar.years[0]}-01-01`, to: `${calendar.years[1]}-12-31` };
  return {
    id: calendar.id,
    name: calendar.name,
    kind: calendar.kind,
    timezone: calendar.timezone,
    weekend: calendar.weekend,
    closes_on_holidays: calendar.years !== null,
    revision: 12,
    revised_at: "2025-12-01T06:00:00Z",
    coverage,
    ...(calendar.hours === undefined ? {} : { operating_hours: calendar.hours }),
  };
}

function checkDay(res, calendars, iso, combine, asOf) {
  for (const calendar of calendars) {
    if (!covered(calendar, iso)) {
      problem(res, 422, "out_of_range", `${iso} is outside ${calendar.id}'s coverage`);
      return null;
    }
  }
  const days = calendars.map((calendar) => calendarDay(calendar, iso, asOf));
  const open = combine === "any_open" ? days.some((d) => d.is_open) : days.every((d) => d.is_open);
  return { date: iso, is_business_day: open, calendars: days };
}

function shift(res, calendars, start, days, combine, asOf) {
  let current = checkDay(res, calendars, start, combine, asOf);
  if (current === null) return null;
  const skipped = [];
  let remaining = Math.abs(days);
  const step = days >= 0 ? 1 : -1;
  while (remaining > 0) {
    current = checkDay(res, calendars, addDays(current.date, step), combine, asOf);
    if (current === null) return null;
    if (current.is_business_day) remaining--;
    else skipped.push({ date: current.date, calendars: current.calendars });
  }
  return { start, days, date: current.date, combine, calendars: current.calendars, skipped };
}

// ---- routes ----
async function route(req, res, url) {
  const path = url.pathname;
  const q = url.searchParams;
  const asOf = q.get("as_of") ?? undefined;
  const method = req.method;
  const body = method === "POST" || method === "PUT" ? await readJson(req) : undefined;

  if (method === "POST" && path === "/__reset") {
    state = freshState();
    return send(res, 204);
  }
  // The pipeline's site files (llms.txt, sitemaps, robots.txt), as the public bucket serves them.
  if (method === "GET" && path.startsWith("/site/")) {
    const name = path.slice("/site/".length);
    if (!/^(?:[a-z]+\/)?[A-Za-z0-9][A-Za-z0-9.-]*\.(txt|xml|csv|json|ics)$/.test(name)) {
      return send(res, 404);
    }
    try {
      const body = readFileSync(join(SITE_FILES, name));
      res.writeHead(200, { "Content-Type": "application/octet-stream" });
      return res.end(body);
    } catch {
      return send(res, 404);
    }
  }
  const closures = path.match(/^\/v1\/calendars\/([^/]+)\/closures$/);
  if (method === "GET" && closures) {
    const calendar = calendarOr404(res, closures[1]);
    if (!calendar) return;
    const from = q.get("from") ?? "";
    const to = q.get("to") ?? "";
    if (!covered(calendar, from) || !covered(calendar, to) || to < from) {
      return problem(res, 422, "out_of_range", `${from} to ${to} is outside ${calendar.id}`);
    }
    const list = Object.keys(calendar.holidays)
      .filter((date) => date >= from && date <= to)
      .sort()
      .map((date) => {
        const day = calendarDay(calendar, date, asOf);
        return {
          date,
          reason: day.reason,
          basis: day.basis,
          revision: day.revision,
          recorded_from: day.recorded_from,
          sources: day.sources,
        };
      });
    return send(
      res,
      200,
      answered(
        { calendar_id: calendar.id, from, to, closures: list, calendar_revision: 12 },
        { path, from, to },
        [calendar.id],
        asOf,
      ),
    );
  }
  if (method === "GET" && path === "/v1/calendars") {
    const ids = CALENDARS.map((c) => c.id);
    return send(res, 200, answered({ calendars: CALENDARS.map(summary) }, { path }, ids, asOf));
  }
  let match = path.match(/^\/v1\/calendars\/([^/]+)\/days$/);
  if (method === "GET" && match) {
    const calendar = calendarOr404(res, match[1]);
    if (!calendar) return;
    const from = q.get("from") ?? `${TODAY.slice(0, 4)}-01-01`;
    const to = q.get("to") ?? addDays(q.get("from") ?? TODAY, 90);
    if (to < from) return problem(res, 400, "validation_failed", "to must be on or after from");
    const days = [];
    for (let iso = from; iso <= to; iso = addDays(iso, 1)) {
      if (!covered(calendar, iso)) continue;
      const day = calendarDay(calendar, iso, asOf);
      days.push({
        date: iso,
        is_open: day.is_open,
        decided_by: day.decided_by,
        reason: day.reason,
        basis: day.basis,
        sources: day.sources,
        next_business_day: neighbour(calendar, iso, 1, asOf),
        previous_business_day: neighbour(calendar, iso, -1, asOf),
      });
    }
    return send(
      res,
      200,
      answered(
        { calendar_id: calendar.id, today: TODAY, from, to, days },
        { path, from, to },
        [calendar.id],
        asOf,
      ),
    );
  }
  match = path.match(/^\/v1\/calendars\/([^/]+)\/days\/(\d{4}-\d{2}-\d{2})$/);
  if (method === "GET" && match) {
    const calendar = calendarOr404(res, match[1]);
    if (!calendar) return;
    const day = checkDay(res, [calendar], match[2], "all_open", asOf);
    if (day === null) return;
    return send(res, 200, answered({ ...day, combine: "all_open" }, { path }, [calendar.id], asOf));
  }
  match = path.match(/^\/v1\/business-days\/(check|add|next)$/);
  if (method === "POST" && match) {
    const calendars = (body?.calendars ?? []).map((id) => CALENDARS.find((c) => c.id === id));
    if (calendars.length === 0 || calendars.some((c) => c === undefined)) {
      return problem(res, 404, "unknown_calendar", "Unknown calendar");
    }
    const combine = body.combine ?? "all_open";
    const at = body.as_of;
    const result =
      match[1] === "check"
        ? checkDay(res, calendars, body.date, combine, at)
        : shift(res, calendars, body.date, match[1] === "next" ? 1 : body.days, combine, at);
    if (result === null) return;
    return send(res, 200, answered({ ...result, combine }, body, body.calendars, at));
  }
  if (method === "GET" && path === "/v1/changes") {
    const since = q.get("since") ?? "1970-01-01T00:00:00Z";
    const calendars = q.getAll("calendars");
    const types = q.getAll("types");
    const changes = CHANGES.filter(
      (c) =>
        c.published_at >= since &&
        (calendars.length === 0 || calendars.includes(c.calendar_id)) &&
        (types.length === 0 || types.includes(c.type)),
    );
    return send(res, 200, {
      changes,
      next_cursor: null,
      since,
      limit: Number(q.get("limit") ?? 50),
    });
  }
  if (method === "POST" && path === "/v1/receipts/verify") {
    const known = state.receipts.get(body?.id);
    return send(res, 200, { valid: known !== undefined && known === body?.payload_hash });
  }
  if (path.startsWith("/v1/account/")) return accountRoutes(req, res, path, method, body);
  if (path.startsWith("/v1/admin/")) return adminRoutes(req, res, path, method, body, q);
  return problem(res, 404, "not_found", "No such route");
}

function accountRoutes(req, res, path, method, body) {
  const who = claims(req);
  if (who === null) return problem(res, 401, "unauthorized", "Authentication is required");
  const mine = account(who.sub);
  const key = req.headers["idempotency-key"];
  const reused = () => {
    if (method !== "POST" || key === undefined) return false;
    if (mine.idempotency.has(`${path}:${key}`)) return true;
    mine.idempotency.add(`${path}:${key}`);
    return false;
  };
  if (path === "/v1/account/api-keys") {
    if (method === "GET") return send(res, 200, mine.keys);
    if (reused()) return problem(res, 409, "idempotency_key_reused", "Key reused");
    const prefix = `zw_live_${randomUUID().replace(/-/g, "").slice(0, 8)}`;
    const created = {
      id: randomUUID(),
      name: body.name,
      prefix,
      created_at: NOW,
      last_used_at: null,
      revoked_at: null,
    };
    mine.keys.unshift(created);
    return send(res, 201, { ...created, key: `${prefix}_${hashLike(prefix).slice(0, 43)}` });
  }
  let match = path.match(/^\/v1\/account\/api-keys\/([^/]+)$/);
  if (match && method === "DELETE") {
    const found = mine.keys.find((k) => k.id === match[1]);
    if (found) found.revoked_at = NOW;
    return send(res, 204);
  }
  if (path === "/v1/account/webhooks") {
    if (method === "GET") return send(res, 200, mine.webhooks);
    if (reused()) return problem(res, 409, "idempotency_key_reused", "Key reused");
    if (!String(body.url).startsWith("https://"))
      return problem(res, 400, "validation_failed", "The URL must be HTTPS on a public host");
    const created = { id: randomUUID(), url: body.url, events: body.events, created_at: NOW };
    mine.webhooks.unshift(created);
    return send(res, 201, { ...created, secret: `whsec_${hashLike(created.id).slice(0, 43)}` });
  }
  match = path.match(/^\/v1\/account\/webhooks\/([^/]+)(\/test)?$/);
  if (match) {
    const found = mine.webhooks.find((w) => w.id === match[1]);
    if (!found && method !== "DELETE") return problem(res, 404, "not_found", "No such webhook");
    if (match[2]) return send(res, 200, { succeeded: true, response_status: 200 });
    if (method === "PUT")
      return send(res, 200, Object.assign(found, { url: body.url, events: body.events }));
    mine.webhooks = mine.webhooks.filter((w) => w.id !== match[1]);
    return send(res, 204);
  }
  if (path === "/v1/account/alert-rules") {
    if (method === "GET") return send(res, 200, mine.rules);
    if (reused()) return problem(res, 409, "idempotency_key_reused", "Key reused");
    const created = { id: randomUUID(), max_notice_days: null, ...body, created_at: NOW };
    mine.rules.unshift(created);
    return send(res, 201, created);
  }
  match = path.match(/^\/v1\/account\/alert-rules\/([^/]+)$/);
  if (match) {
    const found = mine.rules.find((r) => r.id === match[1]);
    if (method === "PUT") {
      if (!found) return problem(res, 404, "not_found", "No alert rule has that ID");
      return send(res, 200, Object.assign(found, { max_notice_days: null, ...body }));
    }
    mine.rules = mine.rules.filter((r) => r.id !== match[1]);
    return send(res, 204);
  }
  return problem(res, 404, "not_found", "No such route");
}

function adminRoutes(req, res, path, method, body, q) {
  const who = claims(req);
  if (who === null) return problem(res, 401, "unauthorized", "Authentication is required");
  if (!who.roles.includes("reviewer"))
    return problem(res, 403, "forbidden", "The caller lacks the required role");
  const view = (c) => {
    const candidate = { ...c };
    for (const field of ["snapshots", "reviewer", "review_reason", "reviewed_at"])
      delete candidate[field];
    const notice = Math.round((toDate(c.entry_date) - toDate(TODAY)) / DAY_MS);
    return { ...candidate, notice_days: notice, urgent: notice < 14 };
  };
  const detail = (c) => ({
    candidate: view(c),
    current: null,
    snapshots: c.snapshots,
    reviewer: c.reviewer ?? null,
    review_reason: c.review_reason ?? null,
    reviewed_at: c.reviewed_at ?? null,
  });
  if (method === "GET" && path === "/v1/admin/candidates") {
    const status = q.get("status") ?? "pending";
    const list = state.admin.candidates.filter((c) => c.status === status).map(view);
    list.sort(
      (a, b) => Number(b.urgent) - Number(a.urgent) || a.entry_date.localeCompare(b.entry_date),
    );
    return send(res, 200, { candidates: list });
  }
  const match = path.match(/^\/v1\/admin\/candidates\/(\d+)(\/decision)?$/);
  if (match) {
    const candidate = state.admin.candidates.find((c) => c.id === Number(match[1]));
    if (!candidate) return problem(res, 404, "not_found", "No candidate change has that ID");
    if (!match[2]) return send(res, 200, detail(candidate));
    if (candidate.status !== "pending")
      return problem(res, 409, "candidate_not_pending", "The candidate was already reviewed");
    if (!body?.reason?.trim())
      return problem(res, 400, "validation_failed", "A reason is required");
    Object.assign(candidate, {
      status: body.decision === "approve" ? "approved" : "rejected",
      reviewer: who.sub,
      review_reason: body.reason,
      reviewed_at: NOW,
    });
    return send(res, 200, detail(candidate));
  }
  if (method === "GET" && path === "/v1/admin/sources")
    return send(res, 200, { sources: state.admin.sources });
  if (method === "GET" && path === "/v1/admin/releases")
    return send(res, 200, { releases: state.admin.releases });
  return problem(res, 404, "not_found", "No such route");
}

function readJson(req) {
  return new Promise((resolve) => {
    let raw = "";
    req.on("data", (chunk) => (raw += chunk));
    req.on("end", () => {
      try {
        resolve(raw === "" ? {} : JSON.parse(raw));
      } catch {
        resolve({});
      }
    });
  });
}

createServer((req, res) => {
  const url = new URL(req.url ?? "/", "http://localhost");
  if (process.env.MOCK_API_LOG !== undefined) {
    console.warn(`${req.method} ${url.pathname}${url.search}`);
  }
  route(req, res, url).catch((error) => {
    console.error(error);
    problem(res, 500, "internal_error", "Mock failure");
  });
}).listen(PORT, "127.0.0.1", () => {
  console.warn(`Fake Zeitwide API on http://127.0.0.1:${PORT}`);
});
