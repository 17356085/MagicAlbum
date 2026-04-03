import http from "k6/http";
import { check, sleep, fail } from "k6";

function envInt(name, def) {
  const v = __ENV[name];
  if (v === undefined || v === "") return def;
  const n = parseInt(v, 10);
  return Number.isFinite(n) ? n : def;
}

function envFloat(name, def) {
  const v = __ENV[name];
  if (v === undefined || v === "") return def;
  const n = parseFloat(v);
  return Number.isFinite(n) ? n : def;
}

function envJson(name, def) {
  const v = __ENV[name];
  if (v === undefined || v === "") return def;
  try {
    return JSON.parse(v);
  } catch (_) {
    return def;
  }
}

const BASE_URL = __ENV.BASE_URL || "http://nginx:8080";
const USERS = envInt("USERS", 200);
const PASSWORD = __ENV.PASSWORD || "PerfPassw0rd!";
const WRITE_RATE = envFloat("WRITE_RATE", 0.2);
const STAGES = envJson("STAGES", [
  { duration: "2m", target: 50 },
  { duration: "15m", target: 100 },
  { duration: "2m", target: 0 }
]);

export const options = {
  scenarios: {
    ramp: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: STAGES,
      gracefulRampDown: "30s"
    }
  },
  thresholds: {
    http_req_failed: ["rate<=0.001"],
    http_req_duration: ["p(99)<=500", "p(95)<=300"]
  }
};

function apiUrl(path) {
  if (path.startsWith("/")) return `${BASE_URL}${path}`;
  return `${BASE_URL}/${path}`;
}

function randStr(len) {
  const chars = "abcdefghijklmnopqrstuvwxyz0123456789";
  let s = "";
  for (let i = 0; i < len; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return s;
}

function registerUser(runId, idx) {
  const username = `perf_${runId}_${idx}`;
  const phone = `1${String(1000000000 + ((idx + runId) % 9000000000)).padStart(10, "0")}`;
  const email = `${username}@example.com`;
  const payload = JSON.stringify({ username, phone, email, password: PASSWORD });

  const res = http.post(apiUrl("/api/v1/users/register"), payload, {
    headers: { "Content-Type": "application/json" },
    tags: { name: "users_register" }
  });

  if (res.status !== 201 && res.status !== 409) {
    fail(`register failed status=${res.status} body=${res.body}`);
  }
  return { username, phone, email, password: PASSWORD };
}

function loginByEmail(email, password) {
  const res = http.post(
    apiUrl("/api/v1/auth/login"),
    JSON.stringify({ email, password }),
    { headers: { "Content-Type": "application/json" }, tags: { name: "auth_login" } }
  );
  check(res, { "login 200": (r) => r.status === 200 });
  if (res.status !== 200) return null;
  const json = res.json();
  return json && json.accessToken ? json.accessToken : null;
}

function authedHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json"
  };
}

function listSections() {
  const res = http.get(apiUrl("/api/v1/sections?page=1&size=20"), { tags: { name: "sections_list" } });
  check(res, { "sections 200": (r) => r.status === 200 });
  if (res.status !== 200) return [];
  const body = res.json();
  return body && body.items ? body.items : [];
}

function createThread(token, sectionId) {
  const title = `perf thread ${randStr(12)}`;
  const content = `perf content ${randStr(64)}`;
  const res = http.post(
    apiUrl("/api/v1/threads"),
    JSON.stringify({ sectionId, title, content }),
    { headers: authedHeaders(token), tags: { name: "threads_create" } }
  );
  check(res, { "thread created": (r) => r.status === 201 });
  if (res.status !== 201) return null;
  return res.json();
}

function listThreads(sectionId) {
  const res = http.get(
    apiUrl(`/api/v1/threads?sectionId=${sectionId}&page=1&size=10`),
    { tags: { name: "threads_list" } }
  );
  check(res, { "threads 200": (r) => r.status === 200 });
  if (res.status !== 200) return [];
  const body = res.json();
  return body && body.items ? body.items : [];
}

function getThread(id) {
  const res = http.get(apiUrl(`/api/v1/threads/${id}`), { tags: { name: "threads_get" } });
  check(res, { "thread 200": (r) => r.status === 200 });
  if (res.status !== 200) return null;
  return res.json();
}

function createPost(token, threadId) {
  const contentMd = `perf reply ${randStr(48)}`;
  const res = http.post(
    apiUrl(`/api/v1/threads/${threadId}/posts`),
    JSON.stringify({ contentMd }),
    { headers: authedHeaders(token), tags: { name: "posts_create" } }
  );
  check(res, { "post created": (r) => r.status === 201 || r.status === 429 });
  if (res.status !== 201) return null;
  return res.json();
}

function listPosts(threadId) {
  const res = http.get(
    apiUrl(`/api/v1/threads/${threadId}/posts?page=1&size=20`),
    { tags: { name: "posts_list" } }
  );
  check(res, { "posts 200": (r) => r.status === 200 });
  if (res.status !== 200) return [];
  const body = res.json();
  return body && body.items ? body.items : [];
}

export function setup() {
  const runId = envInt("RUN_ID", Date.now());
  const users = [];
  const count = USERS;
  for (let i = 0; i < count; i++) {
    users.push(registerUser(runId, i));
  }
  const sections = listSections().map((s) => s.id).filter((v) => typeof v === "number");
  if (sections.length === 0) fail("no sections available");
  return { runId, users, sections };
}

let token = null;
let picked = null;

export default function (data) {
  if (!picked) {
    picked = data.users[(__VU - 1) % data.users.length];
  }

  if (!token) {
    token = loginByEmail(picked.email, picked.password);
    if (!token) {
      sleep(1);
      return;
    }
  }

  const sectionId = data.sections[Math.floor(Math.random() * data.sections.length)];

  const doWrite = Math.random() < WRITE_RATE;
  if (doWrite) {
    const thread = createThread(token, sectionId);
    if (thread && thread.id) {
      createPost(token, thread.id);
      listPosts(thread.id);
    }
  } else {
    const threads = listThreads(sectionId);
    if (threads.length > 0) {
      const t = threads[Math.floor(Math.random() * threads.length)];
      if (t && t.id) {
        getThread(t.id);
        listPosts(t.id);
      }
    }
  }

  sleep(envFloat("THINK_TIME_S", 0.3));
}

export function handleSummary(data) {
  const ts = new Date().toISOString().replace(/[:.]/g, "-");
  return {
    [`/results/summary-${ts}.json`]: JSON.stringify(data, null, 2)
  };
}
