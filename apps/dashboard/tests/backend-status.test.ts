import assert from "node:assert/strict";
import { createServer } from "node:http";
import test from "node:test";
import { getBackendStatus } from "../lib/backend-status.ts";

const valid = { name: "AI Exchange", stage: "scaffold", simulationAvailable: false };

test("accepts the exact local scaffold response and bypasses caches", async () => {
  let called = false;
  const status = await getBackendStatus(async (input, options) => {
    called = true;
    assert.equal(input, "http://127.0.0.1:8080/api/v1/status");
    assert.equal(options?.cache, "no-store");
    assert.ok(options?.signal instanceof AbortSignal);
    return Response.json(valid);
  });
  assert.ok(called);
  assert.deepEqual(status, valid);
});

test("connection failure becomes unavailable", async () => {
  assert.equal(await getBackendStatus(async () => { throw new TypeError("offline"); }), null);
});

for (const [label, code, body] of [
  ["non-2xx", 503, JSON.stringify(valid)],
  ["malformed JSON", 200, "{"],
  ["null JSON", 200, "null"],
  ["wrong name", 200, JSON.stringify({ ...valid, name: "Other service" })],
  ["wrong stage", 200, JSON.stringify({ ...valid, stage: "running" })],
  ["simulation flag true", 200, JSON.stringify({ ...valid, simulationAvailable: true })],
  ["missing flag", 200, JSON.stringify({ name: valid.name, stage: valid.stage })],
] as const) {
  test(`${label} from an HTTP server becomes unavailable`, async () => {
    const server = createServer((_request, response) => {
      response.writeHead(code, { "content-type": "application/json" });
      response.end(body);
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    try {
      const address = server.address();
      assert.ok(address && typeof address === "object");
      const fixtureUrl = `http://127.0.0.1:${address.port}`;
      assert.equal(await getBackendStatus((_input, options) => fetch(fixtureUrl, options)), null);
    } finally {
      await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
    }
  });
}
