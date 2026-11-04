import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { createConnection } from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const alive = pid => { try { process.kill(pid, 0); return true; } catch { return false; } };
async function listening(port) {
  return new Promise(resolve => {
    const socket = createConnection({ host: '127.0.0.1', port });
    socket.setTimeout(250);
    socket.once('connect', () => { socket.destroy(); resolve(true); });
    socket.once('error', () => { socket.destroy(); resolve(false); });
    socket.once('timeout', () => { socket.destroy(); resolve(false); });
  });
}

for (const signal of ['SIGTERM', 'SIGINT']) {
  test(`${signal} cleans up the smoke runner's server children`, { timeout: 30_000 }, async () => {
    assert.equal(await listening(3000), false, 'Port 3000 must be free');
    assert.equal(await listening(8080), false, 'Port 8080 must be free');
    const runner = spawn(process.execPath, ['scripts/smoke.mjs'], { cwd: root, stdio: 'ignore' });
    const exited = once(runner, 'exit');
    let childPids = [];
    try {
      for (let attempt = 0; attempt < 200; attempt++) {
        assert.equal(runner.exitCode, null, 'Runner exited before termination test');
        if (await listening(3000)) break;
        await delay(20);
      }
      assert.equal(await listening(3000), true, 'Dashboard child did not start');
      const listing = spawnSync('ps', ['-axo', 'pid=,ppid='], { encoding: 'utf8' });
      assert.equal(listing.status, 0);
      childPids = listing.stdout.trim().split('\n')
        .map(line => line.trim().split(/\s+/).map(Number))
        .filter(([, parent]) => parent === runner.pid).map(([pid]) => pid);
      assert.equal(childPids.length, 2, 'Runner must own Java and Next processes');
      runner.kill(signal);
      const [code, received] = await exited;
      assert.ok(code !== 0 || received, 'Interruption must not report success');
      for (let attempt = 0; attempt < 100 && childPids.some(alive); attempt++) await delay(20);
      assert.deepEqual(childPids.filter(alive), [], 'Smoke runner left child processes alive');
      assert.equal(await listening(3000), false);
      assert.equal(await listening(8080), false);
    } finally {
      if (runner.exitCode === null && runner.signalCode === null) {
        runner.kill('SIGKILL');
        await exited;
      }
      for (const pid of childPids.filter(alive)) process.kill(pid, 'SIGKILL');
      for (let attempt = 0; attempt < 100 && (await listening(3000) || await listening(8080)); attempt++) await delay(20);
    }
  });
}
