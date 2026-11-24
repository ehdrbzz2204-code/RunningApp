import { createServer } from "node:http";
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { createAuth, normalizeCredentials, normalizeRegistration } from "./auth.mjs";
import { createAuthStorage } from "./auth-storage.mjs";
import { assessmentRequirements, normalizeGoal, summarizeAssessment } from "./runner-profile.mjs";
import { createStorage } from "./storage.mjs";

const devTokens = new Map(Object.entries(JSON.parse(process.env.RUN_API_TOKENS ?? "{}")));
if ([...devTokens].some(([token, user]) =>
  token.length < 32 || /\s/.test(token) || typeof user !== "string" || !user.trim())) {
  throw new Error("RUN_API_TOKENS must map development tokens of at least 32 characters to user IDs.");
}
if (process.env.NODE_ENV === "production" && devTokens.size) {
  throw new Error("RUN_API_TOKENS is only available for local development.");
}

const databasePath = resolve(process.env.RUN_DB_PATH ?? "backend/data/runs.db");
mkdirSync(dirname(databasePath), { recursive: true });
const db = new DatabaseSync(databasePath);
const storage = createStorage(db);
const auth = createAuth(createAuthStorage(db));

function reply(response, status, body) {
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
  });
  response.end(JSON.stringify(body));
}

async function readJson(request) {
  let size = 0;
  const chunks = [];
  for await (const chunk of request.iterator({ destroyOnReturn: false })) {
    size += chunk.length;
    if (size > 8192) {
      request.resume();
      throw Object.assign(new Error("payload_too_large"), { status: 413 });
    }
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function normalizeRun(input) {
  const { clientRunId, distanceMeters, movingSeconds, type, missionId = null } = input ?? {};
  const validId = (value) => typeof value === "string" && /^[a-zA-Z0-9_-]{1,128}$/.test(value);
  if (!validId(clientRunId) ||
      !Number.isSafeInteger(distanceMeters) || distanceMeters < 0 ||
      !Number.isSafeInteger(movingSeconds) || movingSeconds <= 0 ||
      !["free", "assessment"].includes(type) || missionId !== null) {
    return null;
  }
  return { clientRunId, distanceMeters, movingSeconds, type, missionId };
}

function withJsonBody(normalize, invalidError, handle) {
  return async (request, response, userId) => {
    if (!/^application\/json(?:\s*;|$)/i.test(request.headers["content-type"] ?? "")) {
      reply(response, 415, { error: "expected_json" });
      return;
    }

    let body;
    try {
      body = normalize(await readJson(request));
    } catch (error) {
      reply(response, error.status ?? 400, {
        error: error.status === 413 ? "payload_too_large" : invalidError,
      });
      return;
    }
    if (!body) {
      reply(response, 400, { error: invalidError });
      return;
    }
    await handle(response, userId, body);
  };
}

// Limit expensive password work for this single-process, loopback server.
let authWindowStarted = performance.now();
let authAttempts = 0;
let activeAuthRequests = 0;

function withCredentialWork(handle) {
  return async (response, userId, credentials) => {
    const now = performance.now();
    if (now - authWindowStarted >= 60_000) {
      authWindowStarted = now;
      authAttempts = 0;
    }
    if (authAttempts >= 30 || activeAuthRequests >= 4) {
      reply(response, 429, { error: "too_many_requests" });
      return;
    }
    authAttempts++;
    activeAuthRequests++;
    try {
      await handle(response, userId, credentials);
    } finally {
      activeAuthRequests--;
    }
  };
}

const publicRoutes = new Map([
  ["POST /auth/register", withJsonBody(normalizeRegistration, "invalid_registration", withCredentialWork(async (response, _userId, credentials) => {
    const user = await auth.register(credentials);
    if (!user) {
      reply(response, 409, { error: "email_unavailable" });
      return;
    }
    reply(response, 201, { user });
  }))],
  ["POST /auth/login", withJsonBody(normalizeCredentials, "invalid_login", withCredentialWork(async (response, _userId, credentials) => {
    const session = await auth.login(credentials);
    if (!session) {
      reply(response, 401, { error: "invalid_credentials" });
      return;
    }
    reply(response, 200, session);
  }))],
]);

const routes = new Map([
  ["GET /auth/me", (_request, response, _userId, sessionUser) => {
    if (!sessionUser) {
      reply(response, 401, { error: "unauthorized" });
      return;
    }
    reply(response, 200, { user: sessionUser });
  }],
  ["POST /auth/logout", (_request, response, _userId, sessionUser, token) => {
    if (!sessionUser) {
      reply(response, 401, { error: "unauthorized" });
      return;
    }
    auth.logout(token);
    response.writeHead(204, { "Cache-Control": "no-store" }).end();
  }],
  ["POST /runs", withJsonBody(normalizeRun, "invalid_run", (response, userId, run) => {
    const saved = storage.saveRun(userId, run);
    if (!saved) {
      reply(response, 409, { error: "client_run_id_conflict" });
      return;
    }
    reply(response, saved.created ? 201 : 200, saved.run);
  })],
  ["GET /assessment", (_request, response, userId) => {
    const runs = storage.getAssessmentRuns(userId, assessmentRequirements);
    reply(response, 200, summarizeAssessment(runs));
  }],
  ["GET /goals/active", (_request, response, userId) => {
    reply(response, 200, { goal: storage.getActiveGoal(userId) });
  }],
  ["PUT /goals/active", withJsonBody(normalizeGoal, "invalid_goal", (response, userId, goal) => {
    storage.putActiveGoal(userId, goal);
    reply(response, 200, { goal });
  })],
]);

const server = createServer(async (request, response) => {
  const route = `${request.method} ${request.url}`;
  const publicHandler = publicRoutes.get(route);
  const handle = routes.get(route);
  if (!publicHandler && !handle) {
    reply(response, 404, { error: "not_found" });
    return;
  }
  try {
    if (publicHandler) {
      await publicHandler(request, response);
      return;
    }
    const token = request.headers.authorization?.match(/^Bearer ([^\s]+)$/i)?.[1];
    const sessionUser = auth.authenticate(token);
    const userId = sessionUser?.id ?? devTokens.get(token);
    if (!userId) {
      reply(response, 401, { error: "unauthorized" });
      return;
    }
    await handle(request, response, userId, sessionUser, token);
  } catch (error) {
    console.error("Failed to handle request:", error.code);
    reply(response, 500, { error: "storage_error" });
  }
});

server.listen(Number(process.env.PORT ?? 3000), "127.0.0.1", () => {
  console.log("RunningApp API listening on port", server.address().port);
});
