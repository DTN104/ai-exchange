import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createWriteStream } from 'node:fs';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const logs = await mkdtemp(join(tmpdir(), 'ai-exchange-smoke-'));
const children = [];
const streams = [];
let cleanupPromise;

function cleanup() {
  return cleanupPromise ??= (async () => {
    await Promise.all(children.map(stop));
    await Promise.all(streams.map(stream => new Promise(resolve => stream.end(resolve))));
    await rm(logs, { recursive: true, force: true });
  })();
}

for (const [signal, code] of [['SIGINT', 130], ['SIGTERM', 143]]) {
  process.once(signal, () => {
    void cleanup().then(() => process.exit(code), error => {
      console.error(error);
      process.exit(code);
    });
  });
}

async function requireFreePort(port) {
  const probe = createServer();
  await new Promise((resolve, reject) => {
    probe.once('error', reject);
    probe.listen(port, '127.0.0.1', resolve);
  });
  await new Promise((resolve, reject) => probe.close(error => error ? reject(error) : resolve()));
}

function start(name, command, args, cwd) {
  assert.equal(cleanupPromise, undefined, 'Cannot start a server during cleanup');
  const log = createWriteStream(join(logs, `${name}.log`), { flags: 'a' });
  streams.push(log);
  const child = spawn(command, args, { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.pipe(log, { end: false });
  child.stderr.pipe(log, { end: false });
  child.on('error', error => { child.startError = error; });
  children.push(child);
  return child;
}

async function stop(child) {
  if (!child?.pid || child.exitCode !== null || child.signalCode !== null) return;
  const exited = once(child, 'exit');
  child.kill('SIGTERM');
  const force = setTimeout(() => child.kill('SIGKILL'), 5_000);
  try { await exited; } finally { clearTimeout(force); }
}

async function waitFor(url, child) {
  for (let attempt = 0; attempt < 120; attempt++) {
    if (child.startError) throw child.startError;
    assert.equal(child.exitCode, null, `Process exited while waiting for ${url}`);
    try {
      if ((await fetch(url, { signal: AbortSignal.timeout(1_000) })).ok) return;
    } catch { /* A new process may not be listening yet. */ }
    await delay(250);
  }
  assert.fail(`Timed out waiting for ${url}`);
}

const startJava = () => start('server', 'java', ['-Xmx512m', '-jar', 'apps/server/target/server-0.1.0.jar'], root);
const dashboardUrl = 'http://127.0.0.1:3000';
const statusUrl = 'http://127.0.0.1:8080/api/v1/status';
const get = url => fetch(url, { signal: AbortSignal.timeout(5_000) });

async function assertPage(label, forbidden) {
  const response = await get(dashboardUrl);
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.ok(html.includes(label), `Missing page state: ${label}`);
  assert.ok(!html.includes(forbidden), `Stale page state: ${forbidden}`);
  assert.ok(html.includes('Simulation not implemented yet.'));
}

try {
  await requireFreePort(8080);
  await requireFreePort(3000);
  let java = startJava();
  const web = start('dashboard', process.execPath, ['node_modules/next/dist/bin/next', 'start', '--hostname', '127.0.0.1'], join(root, 'apps/dashboard'));
  await waitFor(statusUrl, java);
  await waitFor(dashboardUrl, web);
  const status = await get(statusUrl);
  assert.equal(status.status, 200);
  assert.deepEqual(await status.json(), { name: 'AI Exchange', stage: 'scaffold', simulationAvailable: false });
  const health = await get('http://127.0.0.1:8080/actuator/health');
  assert.equal(health.status, 200);
  assert.equal((await health.json()).status, 'UP');
  await assertPage('Backend connected', 'Backend unavailable');
  await stop(java);
  await assertPage('Backend unavailable', 'Backend connected');
  java = startJava();
  await waitFor(statusUrl, java);
  await assertPage('Backend connected', 'Backend unavailable');
  console.log('Smoke passed: live API, dashboard, offline fallback and recovery.');
} catch (error) {
  for (const name of ['server', 'dashboard']) {
    console.error(await readFile(join(logs, `${name}.log`), 'utf8').catch(() => ''));
  }
  throw error;
} finally {
  await cleanup();
}
