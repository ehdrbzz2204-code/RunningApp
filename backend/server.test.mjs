import { spawn } from "node:child_process";
import { once } from "node:events";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

test("run uploads are isolated by user and idempotent across restarts", async () => {
  const directory = mkdtempSync(join(tmpdir(), "running-api-"));
  const token = "a".repeat(32);
  const otherToken = "b".repeat(32);
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
    endpoint = `http://127.0.0.1:${port}/runs`;
  }

  async function stop() {
    const handle = processHandle;
    processHandle = undefined;
    if (handle.exitCode !== null || handle.signalCode !== null) return;
    const exited = once(handle, "exit");
    handle.kill();
    await exited;
  }

  const run = { clientRunId: "test-1", distanceMeters: 3000, movingSeconds: 1260, type: "assessment" };
  const upload = (bearer, body = run) => fetch(endpoint, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${bearer}` },
    body: JSON.stringify(body),
  });

  try {
    await start();
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
    await stop();
    await start();
    const replay = await upload(token);
    assert.equal(replay.status, 200);
    assert.equal((await replay.json()).id, records[0].id);
  } finally {
    if (processHandle) await stop();
    rmSync(directory, { recursive: true, force: true });
  }
});
