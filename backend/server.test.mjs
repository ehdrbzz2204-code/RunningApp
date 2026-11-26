import { spawn } from "node:child_process";
import { once } from "node:events";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { createHash } from "node:crypto";
import { test } from "node:test";

const token = "a".repeat(32);
const otherToken = "b".repeat(32);
const run = { clientRunId: "test-1", distanceMeters: 3000, movingSeconds: 1260, type: "assessment" };

async function withServer(check, { enableDevTokens = true } = {}) {
  const directory = mkdtempSync(join(tmpdir(), "running-api-"));
  const databasePath = join(directory, "runs.db");
  let processHandle;
  let endpoint;

  async function start() {
    processHandle = spawn(process.execPath, ["backend/server.mjs"], {
      env: {
        ...process.env,
        PORT: "0",
        RUN_DB_PATH: databasePath,
        RUN_API_TOKENS: JSON.stringify(enableDevTokens ? { [token]: "one", [otherToken]: "two" } : {}),
        NODE_ENV: "test",
      },
      stdio: ["ignore", "pipe", "inherit"],
    });
    const port = await Promise.race([
      once(processHandle.stdout, "data").then(([chunk]) => String(chunk).trim().split(" ").at(-1)),
      once(processHandle, "exit").then(([code]) => { throw new Error(`Server exited with ${code}`); }),
    ]);
    endpoint = `http://127.0.0.1:${port}`;
  }

  async function stop() {
    const handle = processHandle;
    processHandle = undefined;
    if (handle.exitCode !== null || handle.signalCode !== null) return;
    const exited = once(handle, "exit");
    handle.kill();
    await exited;
  }

  const request = (path, bearer, method = "GET", body) => fetch(endpoint + path, {
    method,
    headers: bearer
      ? { "Content-Type": "application/json", Authorization: `Bearer ${bearer}` }
      : { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  try {
    await start();
    await check({
      request,
      databasePath,
      upload: (bearer, body = run) => request("/runs", bearer, "POST", body),
      restart: async () => {
        await stop();
        await start();
      },
    });
  } finally {
    if (processHandle) await stop();
    rmSync(directory, { recursive: true, force: true });
  }
}

test("run uploads are isolated by user and idempotent across restarts", async () => {
  await withServer(async ({ upload, restart }) => {
    const responses = await Promise.all(Array.from({ length: 8 }, () => upload(token)));
    assert.deepEqual(responses.map((response) => response.status).sort(),
      [200, 200, 200, 200, 200, 200, 200, 201]);
    const records = await Promise.all(responses.map((response) => response.json()));
    assert.equal(new Set(records.map((record) => record.id)).size, 1);
    assert.equal((await upload(token, { ...run, distanceMeters: 3010 })).status, 409);
    assert.equal((await upload(token, { ...run, movingSeconds: 0 })).status, 400);
    assert.equal((await upload("bad")).status, 401);
    const otherUser = await upload(otherToken);
    assert.equal(otherUser.status, 201);
    assert.notEqual((await otherUser.json()).id, records[0].id);
    await restart();
    const replay = await upload(token);
    assert.equal(replay.status, 200);
    assert.equal((await replay.json()).id, records[0].id);
  });
});

test("assessment counts the first three qualifying runs once and uses distance-weighted pace", async () => {
  await withServer(async ({ request, upload }) => {
    const assessment = async (bearer = token) => {
      const response = await request("/assessment", bearer);
      assert.equal(response.status, 200);
      return response.json();
    };
    assert.deepEqual(await assessment(), {
      completedRuns: 0,
      requiredRuns: 3,
      status: "in_progress",
      baselinePaceSecondsPerKm: null,
    });
    assert.equal((await request("/assessment", "invalid")).status, 401);
    assert.equal((await upload(token, { ...run, clientRunId: "short", distanceMeters: 2999 })).status, 201);
    assert.equal((await upload(token, { ...run, clientRunId: "free", type: "free" })).status, 201);
    assert.equal((await upload(token)).status, 201);
    assert.equal((await upload(token)).status, 200);
    assert.equal((await upload(otherToken)).status, 201);
    assert.equal((await assessment()).completedRuns, 1);

    assert.equal((await upload(token, { ...run, clientRunId: "second", distanceMeters: 4000, movingSeconds: 1600 })).status, 201);
    assert.equal((await assessment()).baselinePaceSecondsPerKm, null);
    assert.equal((await upload(token, { ...run, clientRunId: "third", movingSeconds: 1350 })).status, 201);
    const completed = {
      completedRuns: 3,
      requiredRuns: 3,
      status: "completed",
      baselinePaceSecondsPerKm: 421,
    };
    assert.deepEqual(await assessment(), completed);
    assert.equal((await upload(token, { ...run, clientRunId: "fourth", movingSeconds: 2000 })).status, 201);
    assert.deepEqual(await assessment(), completed);
    assert.equal((await assessment(otherToken)).completedRuns, 1);
  });
});

test("active goals can be replaced, survive a restart, and remain private to each user", async () => {
  await withServer(async ({ request, restart }) => {
    const path = "/goals/active";
    const distance = { type: "distance", targetDistanceMeters: 5000 };
    const pace = { type: "pace", targetPaceSecondsPerKm: 390 };
    assert.deepEqual(await (await request(path, token)).json(), { goal: null });
    assert.equal((await request(path, "invalid", "PUT", distance)).status, 401);
    const created = await request(path, token, "PUT", distance);
    assert.equal(created.status, 200);
    assert.deepEqual(await created.json(), { goal: distance });

    for (const invalid of [
      null, { ...distance, targetDistanceMeters: 0 }, { ...pace, targetPaceSecondsPerKm: 4.2 },
      { type: "other", targetDistanceMeters: 5000 }, { ...distance, targetPaceSecondsPerKm: 390 },
    ]) {
      const rejected = await request(path, token, "PUT", invalid);
      assert.equal(rejected.status, 400);
      assert.deepEqual(await rejected.json(), { error: "invalid_goal" });
    }
    assert.deepEqual(await (await request(path, token)).json(), { goal: distance });
    assert.deepEqual(await (await request(path, otherToken)).json(), { goal: null });
    assert.equal((await request(path, otherToken, "PUT", distance)).status, 200);
    const updated = await request(path, token, "PUT", pace);
    assert.equal(updated.status, 200);
    assert.deepEqual(await updated.json(), { goal: pace });
    await restart();
    assert.deepEqual(await (await request(path, token)).json(), { goal: pace });
    assert.deepEqual(await (await request(path, otherToken)).json(), { goal: distance });
  });
});

test("registered accounts keep distinct data and can revoke or expire their sessions", async () => {
  await withServer(async ({ request, upload, restart, databasePath }) => {
    const credentials = { email: " Runner@Example.test ", password: "test-runner-2026-example" };
    const other = { ...credentials, email: "other@example.test" };
    const post = (path, body) => request(path, null, "POST", body);

    const invalidRegistration = await post("/auth/register", { ...credentials, password: "too short" });
    assert.equal(invalidRegistration.status, 400);
    const registered = await post("/auth/register", credentials);
    assert.equal(registered.status, 201);
    const { user } = await registered.json();
    assert.deepEqual(user, { id: user.id, email: "runner@example.test" });
    assert.equal((await post("/auth/register", { ...credentials, email: user.email })).status, 409);
    assert.equal((await post("/auth/register", other)).status, 201);

    for (const invalid of [
      { ...credentials, password: "wrong password" },
      { ...credentials, email: "unknown@example.test" },
    ]) {
      const denied = await post("/auth/login", invalid);
      assert.equal(denied.status, 401);
      assert.deepEqual(await denied.json(), { error: "invalid_credentials" });
    }

    const loggedIn = await post("/auth/login", { ...credentials, email: user.email });
    assert.equal(loggedIn.status, 200);
    assert.equal(loggedIn.headers.get("cache-control"), "no-store");
    const session = await loggedIn.json();
    assert.deepEqual(session.user, user);
    assert.equal(session.tokenType, "Bearer");
    assert.ok(Date.parse(session.expiresAt) > Date.now());
    const otherSession = await (await post("/auth/login", other)).json();
    assert.notEqual(otherSession.user.id, user.id);
    assert.equal((await request("/auth/me", token)).status, 401);

    const goal = { type: "distance", targetDistanceMeters: 5000 };
    assert.equal((await upload(session.accessToken)).status, 201);
    assert.equal((await request("/goals/active", session.accessToken, "PUT", goal)).status, 200);
    assert.deepEqual(await (await request("/goals/active", otherSession.accessToken)).json(), { goal: null });
    assert.equal((await (await request("/assessment", otherSession.accessToken)).json()).completedRuns, 0);
    await restart();
    assert.deepEqual(await (await request("/auth/me", session.accessToken)).json(), { user });
    assert.equal((await upload(session.accessToken)).status, 200);

    const db = new DatabaseSync(databasePath);
    try {
      const stored = db.prepare("SELECT * FROM users WHERE id = ?").get(user.id);
      assert.equal(stored.password_hash.length, 64);
      assert.equal(stored.password_salt.length, 16);
      const hash = createHash("sha256").update(otherSession.accessToken).digest("hex");
      assert.equal(db.prepare("UPDATE auth_sessions SET expires_at = 0 WHERE token_hash = ?").run(hash).changes, 1);
    } finally {
      db.close();
    }
    assert.equal((await request("/auth/me", otherSession.accessToken)).status, 401);
    assert.equal((await request("/auth/logout", session.accessToken, "POST")).status, 204);
    assert.equal((await upload(session.accessToken)).status, 401);
    const signedBackIn = await (await post("/auth/login", credentials)).json();
    assert.deepEqual(signedBackIn.user, user);
    assert.deepEqual(await (await request("/goals/active", signedBackIn.accessToken)).json(), { goal });
  }, { enableDevTokens: false });
});

test("mission results use the original conditions and apply only once after retry", async () => {
  await withServer(async ({ request, upload, restart, databasePath }) => {
    const date = "2026-11-27";
    const recommend = () => request("/missions/recommend", token, "POST", { date });
    assert.equal((await (await recommend()).json()).status, "locked");
    for (let i = 0; i < 3; i++) {
      assert.equal((await upload(token, { ...run, clientRunId: `assessment-${i}` })).status, 201);
    }
    assert.equal((await (await recommend()).json()).status, "needs_goal");
    const goal = { type: "distance", targetDistanceMeters: 5000 };
    await request("/goals/active", token, "PUT", goal);
    const { missions } = await (await recommend()).json();
    const [mission, second] = missions;
    assert.deepEqual((await (await recommend()).json()).missions, missions);
    const start = (bearer, id) => request("/missions/start", bearer, "POST", { missionId: id });
    assert.equal((await start(otherToken, mission.id)).status, 404);

    const missionRun = {
      clientRunId: "offline-mission", type: "mission", missionId: mission.id,
      distanceMeters: mission.targetDistanceMeters,
      movingSeconds: Math.floor(mission.targetDistanceMeters * mission.recommendedPaceSecondsPerKm / 1000),
    };
    assert.equal((await upload(token, missionRun)).status, 409);
    assert.equal((await start(token, mission.id)).status, 200);
    assert.equal((await upload(otherToken, missionRun)).status, 404);
    const replays = await Promise.all([upload(token, missionRun), upload(token, missionRun)]);
    assert.deepEqual(replays.map((response) => response.status).sort(), [200, 201]);
    const [first, retry] = await Promise.all(replays.map((response) => response.json()));
    assert.deepEqual(first, retry);
    assert.equal(first.missionResult.outcome, "success");
    assert.equal((await upload(token, { ...missionRun, clientRunId: "second-attempt" })).status, 409);
    assert.equal((await upload(token, { ...missionRun, movingSeconds: missionRun.movingSeconds + 1 })).status, 409);

    assert.equal((await start(token, second.id)).status, 200);
    await request("/goals/active", token, "PUT", { ...goal, targetDistanceMeters: 8000 });
    const failedRun = { ...missionRun, clientRunId: "late-mission", missionId: second.id, distanceMeters: 0 };
    const late = await upload(token, failedRun);
    assert.equal(late.status, 201);
    assert.equal((await late.json()).missionResult.outcome, "failed");
    await restart();
    assert.deepEqual(await (await upload(token, missionRun)).json(), first);

    const db = new DatabaseSync(databasePath);
    try {
      const { payload } = db.prepare("SELECT payload FROM mission_training WHERE user_id = 'one'").get();
      assert.equal(JSON.parse(payload).completedMissionCount, 1);
      assert.equal(db.prepare("SELECT count(*) AS count FROM runs WHERE client_run_id = 'second-attempt'").get().count, 0);
    } finally {
      db.close();
    }
  });
});

test("next recommendations differ after successful and unsuccessful mission streaks", async () => {
  await withServer(async ({ request, upload, restart }) => {
    const recommend = async (bearer, date) => {
      const response = await request("/missions/recommend", bearer, "POST", { date });
      assert.equal(response.status, 200);
      const recommendation = await response.json();
      assert.equal(recommendation.status, "ready");
      return recommendation.missions;
    };
    const goal = { type: "distance", targetDistanceMeters: 5000 };
    for (const bearer of [token, otherToken]) {
      for (let i = 0; i < 3; i++) {
        assert.equal((await upload(bearer, { ...run, clientRunId: `assessment-${i}` })).status, 201);
      }
      assert.equal((await request("/goals/active", bearer, "PUT", goal)).status, 200);
    }
    const [initial] = await recommend(token, "2026-11-24");

    for (const date of ["2026-11-24", "2026-11-25"]) {
      for (const [bearer, succeeds] of [[token, true], [otherToken, false]]) {
        const [mission] = await recommend(bearer, date);
        const started = await request("/missions/start", bearer, "POST", { missionId: mission.id });
        assert.equal(started.status, 200);
        const response = await upload(bearer, {
          type: "mission",
          missionId: mission.id,
          clientRunId: `mission-${date}`,
          distanceMeters: succeeds ? mission.targetDistanceMeters : 0,
          movingSeconds: Math.floor(mission.targetDistanceMeters * mission.recommendedPaceSecondsPerKm / 1000),
        });
        assert.equal(response.status, 201);
        assert.equal((await response.json()).missionResult.outcome, succeeds ? "success" : "failed");
      }
    }

    await restart();
    const afterSuccess = await recommend(token, "2026-11-26");
    const afterFailure = await recommend(otherToken, "2026-11-26");
    assert.equal(afterSuccess.length, 2);
    assert.equal(afterSuccess[0].targetDistanceMeters, initial.targetDistanceMeters + 250);
    assert.equal(afterFailure.length, 1);
    assert.ok(afterFailure[0].targetDistanceMeters < initial.targetDistanceMeters);
  });
});
