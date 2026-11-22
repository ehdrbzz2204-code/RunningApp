import { spawn } from "node:child_process";
import { once } from "node:events";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

const token = "a".repeat(32);
const otherToken = "b".repeat(32);
const run = { clientRunId: "test-1", distanceMeters: 3000, movingSeconds: 1260, type: "assessment" };

async function withServer(check) {
  const directory = mkdtempSync(join(tmpdir(), "running-api-"));
  let processHandle;
  let endpoint;

  async function start() {
    processHandle = spawn(process.execPath, ["backend/server.mjs"], {
      env: {
        ...process.env,
        PORT: "0",
        RUN_DB_PATH: join(directory, "runs.db"),
        RUN_API_TOKENS: JSON.stringify({ [token]: "one", [otherToken]: "two" }),
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
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${bearer}` },
    body: JSON.stringify(body),
  });

  try {
    await start();
    await check({
      request,
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
