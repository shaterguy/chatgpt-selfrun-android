import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { ChromiumManager } from '../src/browser/cdp.mjs';

const fixture = fileURLToPath(new URL('../tools/test-fixtures/owned-cdp-process.mjs', import.meta.url));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function makeManager(t, settings = {}) {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'selfrun-owned-cdp-'));
  const reservation = net.createServer();
  reservation.listen(0, '127.0.0.1');
  await once(reservation, 'listening');
  const browserPort = reservation.address().port;
  await new Promise((resolve) => reservation.close(resolve));
  const browserProfile = path.join(dataDir, 'profile');
  fs.mkdirSync(browserProfile);
  fs.writeFileSync(path.join(browserProfile, 'fixture-settings.json'), JSON.stringify(settings));
  const launcher = path.join(dataDir, 'chromium-fixture');
  const shell=process.env.PREFIX&&fs.existsSync(path.join(process.env.PREFIX,'bin/sh'))?path.join(process.env.PREFIX,'bin/sh'):'/bin/sh';
  fs.writeFileSync(launcher, `#!${shell}\nexec '${process.execPath}' '${fixture}' "$@"\n`, { mode: 0o700 });
  const config = {
    dataDir, browserPort, browserProfile, chromiumCommand: launcher,
    browserLogFile: path.join(dataDir, 'browser.log'),
    browserActivityDir: path.join(dataDir, 'activity'),
    browserTargetIdleMs: 1000, browserTargetSweepMs: 60000,
  };
  const manager = new ChromiumManager(config);
  const managers = [manager];
  const children = [];
  t.after(async () => {
    for (const item of managers) {
      clearInterval(item.idleSweepTimer);
      if (item.child) children.push(item.child);
    }
    for (const child of new Set(children)) {
      if (child.exitCode !== null || child.signalCode !== null) continue;
      const exited = once(child, 'exit');
      child.kill('SIGKILL');
      await exited;
    }
    fs.rmSync(dataDir, { recursive: true, force: true });
  });
  return { manager, config, managers, children };
}

async function externalBrowser(config, children) {
  const child = spawn(config.chromiumCommand, [
    `--remote-debugging-port=${config.browserPort}`,
    `--user-data-dir=${config.browserProfile}`,
    'about:blank',
  ], { stdio: 'ignore' });
  children.push(child);
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${config.browserPort}/json/version`);
      if (response.ok) return child;
    } catch {}
    await sleep(10);
  }
  throw new Error('Fixture did not start');
}

test('idle cleanup preserves manually opened and touched tabs', async (t) => {
  const { manager, config, children } = await makeManager(t);
  await externalBrowser(config, children);
  manager.touchTarget('startup', true);
  const old = new Date(Date.now() - 5000);
  fs.utimesSync(path.join(config.browserActivityDir, 'startup'), old, old);
  assert.deepEqual(await manager.closeIdleTargets(), { closed: [] });
  assert.deepEqual((await manager.listExistingTargets()).map((target) => target.id), ['startup']);
});

test('creation proof survives manager restart without adopting manually touched targets', async (t) => {
  const { manager, config, managers, children } = await makeManager(t);
  await externalBrowser(config, children);
  const target = await manager.createTarget('https://example.test/owned');
  manager.touchTarget('startup', true);
  const restored = new ChromiumManager(config);
  managers.push(restored);
  assert.equal(restored.isOwnedTarget(target.id), true);
  assert.equal(restored.isOwnedTarget('startup'), false);
  const result = await restored.closeOwnedTarget(target.id, { guard: () => true });
  assert.equal(result.closed, true);
  assert.equal(restored.isOwnedTarget(target.id), false);
  assert.equal((await restored.listExistingTargets()).some((item) => item.id === 'startup'), true);
});

test('guard is revalidated after listing and immediately before target close', async (t) => {
  const { manager, config, children } = await makeManager(t);
  await externalBrowser(config, children);
  const target = await manager.createTarget();
  const list = manager.listExistingTargets.bind(manager);
  let current = true;
  manager.listExistingTargets = async () => {
    const targets = await list();
    current = false;
    return targets;
  };
  const result = await manager.closeOwnedTarget(target.id, { guard: () => current });
  assert.equal(result.closed, false);
  assert.equal(manager.isOwnedTarget(target.id), true);
  assert.equal((await list()).some((item) => item.id === target.id), true);
});

test('missing owned target is safely forgotten without starting a browser', async (t) => {
  const { manager } = await makeManager(t);
  const target = await manager.createTarget();
  await fetch(`${manager.baseUrl}/json/close/${target.id}`);
  const result = await manager.closeOwnedTarget(target.id, { guard: () => true });
  assert.equal(result.absent, true);
  assert.equal(manager.isOwnedTarget(target.id), false);
});

test('owned process release waits for actual exit and closes only its proven startup page', async (t) => {
  const { manager } = await makeManager(t);
  await manager.ensureStarted();
  const child = manager.child;
  const result = await manager.releaseIfUnused({ guard: () => true });
  assert.equal(result.released, true);
  assert.equal(result.rootPid, child.pid);
  assert.equal(fs.existsSync(`/proc/${child.pid}`), false);
  assert.equal(await manager.listExistingTargets(), null);
});

test('release preserves restored manual blank tabs even in an owned process', async (t) => {
  const { manager } = await makeManager(t, { targets: [{ id: 'manual-blank', type: 'page', url: 'about:blank' }] });
  await manager.ensureStarted();
  const child = manager.child;
  const result = await manager.releaseIfUnused({ guard: () => true });
  assert.equal(result.released, false);
  assert.equal(fs.existsSync(`/proc/${child.pid}`), true);
  assert.equal((await manager.listExistingTargets()).some((item) => item.id === 'manual-blank'), true);
});

test('release never kills an unproven existing process using the same profile and port', async (t) => {
  const { manager, config, children } = await makeManager(t);
  const child = await externalBrowser(config, children);
  await manager.ensureStarted();
  assert.equal((await manager.releaseIfUnused({ guard: () => true })).released, false);
  assert.equal(fs.existsSync(`/proc/${child.pid}`), true);
  assert.equal((await manager.recycle('test')).recycled, false);
  assert.equal(fs.existsSync(`/proc/${child.pid}`), true);
});

test('persisted exact spawned process proof supports release after a server restart', async (t) => {
  const { manager, config, managers } = await makeManager(t);
  await manager.ensureStarted();
  const child = manager.child;
  const restored = new ChromiumManager(config);
  managers.push(restored);
  const result = await restored.releaseIfUnused({ guard: () => true });
  assert.equal(result.released, true);
  assert.equal(fs.existsSync(`/proc/${child.pid}`), false);
});

test('demand target creation waits for release and starts a new safe browser instance', async (t) => {
  const { manager } = await makeManager(t, { closeDelayMs: 120 });
  await manager.ensureStarted();
  const original = manager.child;
  const release = manager.releaseIfUnused({ guard: () => true });
  const target = await manager.createTarget('https://example.test/new-owner');
  assert.equal((await release).released, true);
  assert.notEqual(manager.child.pid, original.pid);
  assert.equal(manager.isOwnedTarget(target.id), true);
  assert.equal((await manager.listExistingTargets()).some((item) => item.id === target.id), true);
});

test('lifecycle guards preserve active assigned tabs during an idle sweep', async (t) => {
  const { manager, config } = await makeManager(t);
  const target = await manager.createTarget('https://example.test/active');
  const old = new Date(Date.now() - 5000);
  fs.utimesSync(path.join(config.browserActivityDir, target.id), old, old);
  manager.setLifecycleGuards({ canCloseTarget: () => false, canRelease: () => false });
  assert.deepEqual(await manager.closeIdleTargets(), { closed: [] });
  assert.equal((await manager.closeOwnedTarget(target.id)).closed, false);
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.equal((await manager.listExistingTargets()).some((item) => item.id === target.id), true);
});

test('lifecycle release guard also protects the owned startup page', async (t) => {
  const { manager } = await makeManager(t);
  await manager.ensureStarted();
  manager.setLifecycleGuards({ canCloseTarget: () => false, canRelease: () => false });
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.equal((await manager.listExistingTargets()).length, 1);
  manager.setLifecycleGuards({ canCloseTarget: () => false, canRelease: () => true });
  assert.equal((await manager.releaseIfUnused()).released, true);
});

test('process release revalidates lifecycle guard after awaited target listing', async (t) => {
  const { manager } = await makeManager(t);
  await manager.ensureStarted();
  let current = true;
  manager.setLifecycleGuards({ canRelease: () => current });
  const list = manager.listExistingTargets.bind(manager);
  manager.listExistingTargets = async () => {
    const targets = await list();
    current = false;
    return targets;
  };
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.equal((await list()).length, 1);
  assert.equal(fs.existsSync(`/proc/${manager.child.pid}`), true);
});

test('final close guard receives the observed target and preserves repurposed conversations', async (t) => {
  const { manager } = await makeManager(t);
  const target = await manager.createTarget('https://example.test/repurposed');
  const result = await manager.closeOwnedTarget(target.id, {
    guard: (observed) => !observed || observed.url === 'https://example.test/original',
  });
  assert.equal(result.closed, false);
  assert.equal((await manager.listExistingTargets()).some((item) => item.id === target.id), true);
});

test('public close options cannot bypass lifecycle target protection', async (t) => {
  const { manager } = await makeManager(t);
  const target = await manager.createTarget();
  manager.setLifecycleGuards({ canCloseTarget: () => false, canRelease: () => true });
  assert.equal((await manager.closeOwnedTarget(target.id, { startupRelease: true })).closed, false);
});

test('queued demand cannot reuse a dying browser when guard changes after SIGTERM', async (t) => {
  const { manager, config } = await makeManager(t, { sigtermDelayMs: 300 });
  await manager.ensureStarted();
  const oldChild = manager.child;
  const marker = path.join(config.browserProfile, 'sigterm-received');
  const release = manager.releaseIfUnused({ guard: () => !fs.existsSync(marker) });
  for (let attempt = 0; attempt < 100 && !fs.existsSync(marker); attempt += 1) await sleep(5);
  assert.equal(fs.existsSync(marker), true);
  const target = await manager.createTarget('https://example.test/new-demand');
  assert.equal((await release).released, true);
  assert.equal(fs.existsSync(`/proc/${oldChild.pid}`), false);
  assert.notEqual(manager.child.pid, oldChild.pid);
  assert.equal((await manager.listExistingTargets()).some((item) => item.id === target.id), true);
});

test('explicit current request guard permits only creation-proven failure cleanup', async (t) => {
  const { manager, config, children } = await makeManager(t);
  await externalBrowser(config, children);
  const target = await manager.createTarget();
  manager.setLifecycleGuards({ canCloseTarget: () => false, canRelease: () => false });
  assert.equal(await manager.closeTarget('startup', { guard: () => true }), false);
  assert.equal(await manager.closeTarget(target.id, { guard: () => false }), false);
  assert.equal(await manager.closeTarget(target.id, { guard: () => true }), true);
});

test('a new browser instance cannot inherit target proof when target IDs are reused', async (t) => {
  const { manager, config, children } = await makeManager(t);
  const old = await externalBrowser(config, children);
  const target = await manager.createTarget('https://example.test/old');
  const exited = once(old, 'exit');
  old.kill('SIGTERM');
  await exited;
  await externalBrowser(config, children);
  const response = await fetch(`${manager.baseUrl}/json/new?https%3A%2F%2Fexample.test%2Fmanual`, { method: 'PUT' });
  const manual = await response.json();
  assert.equal(manual.id, target.id);
  assert.equal((await manager.closeOwnedTarget(target.id)).closed, false);
  assert.equal(manager.isOwnedTarget(target.id), false);
  assert.equal((await manager.listExistingTargets()).some((item) => item.id === manual.id), true);
});

test('mismatched PID starttime proof cannot release a process after restart', async (t) => {
  const { manager, config, managers } = await makeManager(t);
  await manager.ensureStarted();
  const proofFile = path.join(config.dataDir, `chromium-ownership-${config.browserPort}.json`);
  const proof = JSON.parse(fs.readFileSync(proofFile, 'utf8'));
  proof.process.startTime = String(BigInt(proof.process.startTime) + 1n);
  fs.writeFileSync(proofFile, JSON.stringify(proof));
  const restored = new ChromiumManager(config);
  managers.push(restored);
  assert.equal((await restored.releaseIfUnused()).released, false);
  assert.equal(fs.existsSync(`/proc/${manager.child.pid}`), true);
});

test('owned uses and non-page targets both block process release', async (t) => {
  const { manager } = await makeManager(t, {
    targets: [{ id: 'manual-worker', type: 'service_worker', url: 'https://example.test/worker.js' }],
  });
  const target = await manager.createTarget();
  assert.equal((await manager.releaseIfUnused()).released, false);
  await manager.closeOwnedTarget(target.id);
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.equal(fs.existsSync(`/proc/${manager.child.pid}`), true);
});

test('using the startup page consumes its process-release exception', async (t) => {
  const { manager } = await makeManager(t);
  await manager.ensureStarted();
  manager.touchTarget('startup');
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.equal((await manager.listExistingTargets()).length, 1);
});

test('an in-flight close marks its target unavailable to resume before the response', async (t) => {
  const { manager } = await makeManager(t, { closeDelayMs: 120 });
  const target = await manager.createTarget();
  const closing = manager.closeOwnedTarget(target.id);
  for (let attempt = 0; attempt < 100 && !manager.targetClosing(target.id); attempt += 1) await sleep(5);
  assert.equal(manager.targetClosing(target.id), true);
  await assert.rejects(manager.connectTarget({ ...target, webSocketDebuggerUrl: 'ws://127.0.0.1:1' }), /Target is closing/);
  assert.equal((await closing).closed, true);
});

test('cleanup on a stopped browser never launches or restarts it', async (t) => {
  const { manager } = await makeManager(t);
  assert.equal((await manager.closeOwnedTarget('unknown')).closed, false);
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.equal((await manager.recycle('cleanup')).recycled, false);
  assert.equal(manager.child, null);
  assert.equal(await manager.listExistingTargets(), null);
});

test('owned process ignoring SIGTERM is verified exited after bounded escalation', async (t) => {
  const { manager } = await makeManager(t, { ignoreSigterm: true });
  await manager.ensureStarted();
  const child = manager.child;
  assert.equal((await manager.releaseIfUnused()).released, true);
  assert.equal(fs.existsSync(`/proc/${child.pid}`), false);
  assert.equal(child.signalCode, 'SIGKILL');
});

test('real controller STOP closes its owned target and process while control remains available', async (t) => {
  const { fixture: lifecycleFixture } = await import('./helpers/lifecycle-fixture.mjs');
  const { manager } = await makeManager(t);
  const f = lifecycleFixture();
  f.browser.chromium = manager;
  f.browser.resume = async (args) => {
    f.resumes += 1;
    const target = await manager.createTarget(f.probe.url);
    await args.onTarget?.(target);
    return { target, session: { close() {} }, baseline: { ...f.probe } };
  };
  f.controller = f.recreate();
  t.after(() => f.controller.quiesceForStandby());
  await f.existing();
  const first = manager.child;
  assert.equal((await manager.releaseIfUnused()).released, false);
  await f.stop();
  await f.controller.cleanupTerminalResources();
  assert.equal(f.controller.sessions.size, 0);
  assert.equal(fs.existsSync(`/proc/${first.pid}`), false);
  assert.equal(f.record().retired, true);
  await f.resume();
  assert.equal(f.resumes, 2);
  assert.ok(manager.child?.pid);
  assert.notEqual(manager.child.pid, first.pid);
  assert.equal((await manager.listExistingTargets()).some((target) => target.url === f.probe.url), true);
});

test('an unconfirmed process exit stays quarantined from demand after release timeout', async (t) => {
  const { manager } = await makeManager(t);
  await manager.ensureStarted();
  const child = manager.child;
  // Simulate an OS accepting a signal without process exit (e.g. blocked I/O).
  t.mock.method(process, 'kill', (pid) => {
    assert.equal(pid, child.pid);
    return true;
  });
  const result = await manager.releaseIfUnused();
  assert.equal(result.released, false);
  assert.equal(result.reason, 'process_still_running');
  await assert.rejects(manager.ensureStarted(), /BROWSER_TERMINATING/);
  await assert.rejects(manager.createTarget(), /BROWSER_TERMINATING/);
});

test('unreadable process evidence after restart is not proof of exit and remains quarantined', async (t) => {
  const { manager, config, managers } = await makeManager(t);
  await manager.ensureStarted();
  const child = manager.child;
  const restored = new ChromiumManager(config);
  managers.push(restored);
  let unreadable = false;
  const read = fs.readFileSync.bind(fs);
  t.mock.method(fs, 'readFileSync', (file, ...args) => {
    if (unreadable && String(file) === `/proc/${child.pid}/stat`) {
      throw Object.assign(new Error('Process evidence unavailable'), { code: 'EACCES' });
    }
    return read(file, ...args);
  });
  t.mock.method(process, 'kill', (pid) => {
    assert.equal(pid, child.pid);
    unreadable = true;
    return true;
  });
  assert.equal((await restored.releaseIfUnused()).released, false);
  await assert.rejects(restored.ensureStarted(), /BROWSER_TERMINATING/);
});

test('verified committed Result closes real owned fixture process and a genuinely new request still starts', async (t) => {
  const { fixture: lifecycleFixture } = await import('./helpers/lifecycle-fixture.mjs');
  const { manager } = await makeManager(t);const f=lifecycleFixture();
  f.browser.chromium=manager;
  f.browser.resume=async args=>{
    f.resumes++;const target=await manager.createTarget(f.probe.url);await args.onTarget?.(target);
    return {target,session:{close(){}},baseline:{...f.probe}};
  };
  f.controller=f.recreate();t.after(()=>f.controller.quiesceForStandby());await f.existing();
  const first=manager.child;f.result(true);await f.observe({});await f.controller.cleanupTerminalResources();
  assert.equal(f.record().state,'COMMITTED');assert.equal(f.controller.sessions.size,0);
  assert.equal(fs.existsSync(`/proc/${first.pid}`),false);
  const oldKey=f.record().key;f.probe.url='https://chatgpt.com/c/next-owned';
  Object.assign(f.body,{turn_id:'SAFE-TEST:turn:2',request_id:'SAFE-TEST:turn:2-request',result_document_id:'RESULT-2'});
  f.result(false,'RESULT-2',2);f.path='__SELFRUN_DISPATCH__next-committed.json';
  f.control={...f.control,turn_id:f.body.turn_id,request_id:f.body.request_id,state:'RUNNING',control_epoch:2};
  await f.controller.control(null,f.control,f.transport);await f.existing();
  assert.notEqual(manager.child?.pid,first.pid);assert.equal(f.resumes,2);assert.equal(f.sends,0);
  assert.equal(f.controller.repository.get(oldKey).retired,true);assert.equal(f.record().state,'OBSERVING');
});
