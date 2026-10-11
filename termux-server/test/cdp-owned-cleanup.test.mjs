import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { ChromiumManager, CdpSession } from '../src/browser/cdp.mjs';

const fixture = fileURLToPath(new URL('../tools/test-fixtures/owned-cdp-process.mjs', import.meta.url));
const signalOwnedFixture = process.kill.bind(process);
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
      signalOwnedFixture(child.pid,'SIGKILL');
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
  const exited = once(child, 'exit');
  const restored = new ChromiumManager(config);
  managers.push(restored);
  const result = await restored.releaseIfUnused({ guard: () => true });
  assert.equal(result.released, true);
  // The restored manager has no child handle. A terminated zombie may await the
  // original fixture owner's event-loop reap, but release must never report a live process.
  try {
    const raw = fs.readFileSync(`/proc/${child.pid}/stat`, 'utf8');
    const state = raw.slice(raw.lastIndexOf(')') + 2).trim().split(/\s+/)[0];
    assert.ok(['Z', 'X'].includes(state), `Released process is still live: ${state}`);
  } catch (error) {
    if (!['ENOENT', 'ESRCH'].includes(error.code)) throw error;
  }
  let reapTimer;
  try {
    await Promise.race([exited, new Promise((_, reject) => {
      reapTimer = setTimeout(() => reject(new Error('Fixture child was not reaped within 2 seconds')), 2000);
    })]);
  } finally { clearTimeout(reapTimer); }
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
  assert.equal(f.record(), null);
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

test('explicit DONE closes real owned fixture process and a genuinely new request still starts', async (t) => {
  const { fixture: lifecycleFixture } = await import('./helpers/lifecycle-fixture.mjs');
  const { manager } = await makeManager(t);const f=lifecycleFixture();
  f.browser.chromium=manager;
  f.browser.resume=async args=>{
    f.resumes++;const target=await manager.createTarget(f.probe.url);await args.onTarget?.(target);
    return {target,session:{close(){}},baseline:{...f.probe}};
  };
  f.controller=f.recreate();t.after(()=>f.controller.quiesceForStandby());await f.existing();
  const first=manager.child,oldKey=f.record().key;f.control={...f.control,state:'DONE',control_epoch:2};await f.controller.control(null,f.control,f.transport);await f.controller.cleanupTerminalResources();
  assert.equal(f.record(),null);assert.equal(f.controller.sessions.size,0);
  assert.equal(fs.existsSync(`/proc/${first.pid}`),false);
  f.probe.url='https://chatgpt.com/c/next-owned';
  Object.assign(f.body,{turn_id:'SAFE-TEST:turn:2',request_id:'SAFE-TEST:turn:2-request',result_document_id:'RESULT-2'});
  f.result(false,'RESULT-2',2);f.path='__SELFRUN_DISPATCH__next-committed.json';
  f.control={...f.control,turn_id:f.body.turn_id,request_id:f.body.request_id,state:'RUNNING',control_epoch:2};
  await f.controller.control(null,f.control,f.transport);await f.existing();
  assert.notEqual(manager.child?.pid,first.pid);assert.equal(f.resumes,2);assert.equal(f.sends,0);
  assert.equal(f.controller.repository.get(oldKey),null);assert.equal(f.record().state,'OBSERVING');
});


test('late nonce startup is captured before the newly launched browser is exposed', async (t) => {
  const { manager } = await makeManager(t, { startupAfterLists: 3 });
  await manager.ensureStarted();
  assert.equal(manager.isOwnedTarget('startup'), true);
  const child = manager.child;
  assert.equal((await manager.releaseIfUnused()).released, true);
  assert.equal(fs.existsSync(`/proc/${child.pid}`), false);
});

test('launch suppresses automatic startup windows and zero-page browser remains usable', async (t) => {
  const { manager, config } = await makeManager(t, { honorNoStartupWindow: true });
  await manager.ensureStarted();
  const args = JSON.parse(fs.readFileSync(path.join(config.browserProfile, 'launch-args.json'), 'utf8'));
  assert.equal(args.includes('--no-startup-window'), true);
  assert.deepEqual(await manager.listExistingTargets(), []);
  const target = await manager.createTarget('https://example.test/new-request');
  assert.equal(manager.isOwnedTarget(target.id), true);
  assert.deepEqual((await manager.listExistingTargets()).map((item) => item.id), [target.id]);
  await manager.closeOwnedTarget(target.id);
  assert.equal((await manager.releaseIfUnused()).released, true);
});

test('startup capture never adopts a sole unexpected newtab from a new process', async (t) => {
  const { manager } = await makeManager(t, { startupUrl: 'chrome://newtab/' });
  await manager.ensureStarted();
  assert.equal(manager.isOwnedTarget('startup'), false);
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.equal((await manager.listExistingTargets()).length, 1);
});

test('startup capture never adopts a sole restored plain blank', async (t) => {
  const { manager } = await makeManager(t, { startupUrl: 'about:blank' });
  await manager.ensureStarted();
  assert.equal(manager.isOwnedTarget('startup'), false);
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.equal((await manager.listExistingTargets()).length, 1);
});

test('startup capture does not adopt an existing browser sole newtab', async (t) => {
  const { manager, config, children } = await makeManager(t, { startupUrl: 'chrome://newtab/' });
  const child = await externalBrowser(config, children);
  await manager.ensureStarted();
  assert.equal(manager.isOwnedTarget('startup'), false);
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.equal(fs.existsSync(`/proc/${child.pid}`), true);
});

test('startup capture preserves unrelated pages alongside a fresh newtab', async (t) => {
  const { manager } = await makeManager(t, {
    startupUrl: 'chrome://newtab/',
    targets: [{ id: 'manual', type: 'page', url: 'https://example.test/manual' }],
  });
  await manager.ensureStarted();
  assert.equal(manager.isOwnedTarget('startup'), false);
  assert.equal(manager.isOwnedTarget('manual'), false);
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.equal((await manager.listExistingTargets()).length, 2);
});

test('a manual newtab added after launch never receives the bootstrap release exception', async (t) => {
  const { manager } = await makeManager(t);
  await manager.ensureStarted();
  const response = await fetch(`${manager.baseUrl}/json/new?${encodeURIComponent('chrome://newtab/')}`, { method: 'PUT' });
  const manual = await response.json();
  await manager.ensureStarted();
  assert.equal(manager.isOwnedTarget(manual.id), false);
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.equal((await manager.listExistingTargets()).some((target) => target.id === manual.id), true);
});


test('startup capture cannot resurrect an exception after concurrent target use', async (t) => {
  const { manager } = await makeManager(t);
  const list = manager.listExistingTargets.bind(manager);
  manager.listExistingTargets = async (...args) => {
    const targets = await list(...args);
    if (targets.some((target) => target.id === 'startup')) manager.touchTarget('startup');
    return targets;
  };
  await manager.ensureStarted();
  assert.equal(manager.isOwnedTarget('startup'), false);
  assert.equal((await manager.releaseIfUnused()).released, false);
});

test('expired bootstrap window is never reopened by a later ensure or cleanup', async (t) => {
  const { manager, config } = await makeManager(t, { skipStartup: true });
  await manager.ensureStarted();
  const startupUrl = JSON.parse(fs.readFileSync(path.join(config.browserProfile, 'launch-args.json'), 'utf8')).at(-1);
  const response = await fetch(`${manager.baseUrl}/json/new?${encodeURIComponent(startupUrl)}`, { method: 'PUT' });
  const late = await response.json();
  await manager.ensureStarted();
  assert.equal(manager.isOwnedTarget(late.id), false);
  assert.equal((await manager.releaseIfUnused()).released, false);
});


test('startup capture fails closed if the owned process identity changes during listing', async (t) => {
  const { manager } = await makeManager(t);
  const list = manager.listExistingTargets.bind(manager);
  manager.listExistingTargets = async (...args) => {
    const targets = await list(...args);
    manager.ownership.process.startTime = 'invalidated';
    return targets;
  };
  await manager.ensureStarted();
  assert.equal(manager.isOwnedTarget('startup'), false);
  assert.equal((await manager.releaseIfUnused()).released, false);
});

test('startup capture fails closed if the browser instance changes during listing', async (t) => {
  const { manager } = await makeManager(t);
  const list = manager.listExistingTargets.bind(manager);
  let changed = false;
  manager.listExistingTargets = async (...args) => {
    const targets = await list(...args);
    changed = true;
    return targets;
  };
  const fetchOriginal = globalThis.fetch;
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    const response = await fetchOriginal(url, options);
    if (changed && String(url).endsWith('/json/version')) {
      const version = await response.json();
      return Response.json({ ...version, webSocketDebuggerUrl: `${version.webSocketDebuggerUrl}-changed` });
    }
    return response;
  });
  await manager.ensureStarted();
  assert.equal(manager.isOwnedTarget('startup'), false);
  assert.equal((await manager.releaseIfUnused()).released, false);
});

test('startup capture never retries past an unrelated initial target set', async (t) => {
  const { manager } = await makeManager(t, {
    startupAfterLists: 2,
    targets: [{ id: 'unrelated', type: 'service_worker', url: 'https://example.test/worker' }],
  });
  await manager.ensureStarted();
  await fetch(`${manager.baseUrl}/json/close/unrelated`);
  await manager.listExistingTargets();
  await manager.ensureStarted();
  assert.equal(manager.isOwnedTarget('startup'), false);
  assert.equal((await manager.releaseIfUnused()).released, false);
});

test('concurrent use still prevents startup ownership when its activity-file write fails', async (t) => {
  const { manager, config } = await makeManager(t);
  const open = fs.openSync.bind(fs);
  t.mock.method(fs, 'openSync', (file, ...args) => {
    if (String(file).startsWith(config.browserActivityDir + path.sep)) {
      throw Object.assign(new Error('Activity path temporarily unwritable'), { code: 'EACCES' });
    }
    return open(file, ...args);
  });
  const list = manager.listExistingTargets.bind(manager);
  manager.listExistingTargets = async (...args) => {
    const targets = await list(...args);
    if (targets.some((target) => target.id === 'startup')) manager.touchTarget('startup');
    return targets;
  };
  await manager.ensureStarted();
  assert.equal(manager.isOwnedTarget('startup'), false);
  assert.equal((await manager.releaseIfUnused()).released, false);
});


// The fake process supplies real PID/birth/profile/port proof. These protocol
// doubles model only browser-context lookup and instance-bound target commands.
function approvedHomeProtocol(t, manager, options = {}) {
  const calls = [], closed = [], ownershipAtClose = [];
  t.mock.method(CdpSession.prototype, 'connect', async function () {
    calls.push({ method: 'connect', url: this.wsUrl });
    await options.onConnect?.(this);
    return this;
  });
  t.mock.method(CdpSession.prototype, 'call', async function (method, params = {}) {
    calls.push({ method, params });
    if (method === 'Target.getBrowserContexts') {
      await options.onContexts?.();
      return options.contexts ?? { browserContextIds: [], defaultBrowserContextId: 'default-context' };
    }
    if (method === 'Target.getTargetInfo') {
      const target = (await manager.listExistingTargets()).find(target => target.id === params.targetId);
      if (!target) throw new Error('Target not found');
      const targetInfo = { targetId: target.id, type: target.type, url: target.url,
        browserContextId: 'default-context', attached: false, ...options.targetInfo };
      await options.onTargetInfo?.(targetInfo);
      return { targetInfo };
    }
    if (method === 'Target.getTargets') {
      return { targetInfos: (await manager.listExistingTargets()).map(target => ({ ...target, targetId: target.id })) };
    }
    if (method === 'Target.closeTarget') {
      ownershipAtClose.push(manager.isOwnedTarget(params.targetId));
      await fetch(manager.baseUrl + '/json/close/' + params.targetId);
      closed.push(params.targetId);
      return { success: true };
    }
    throw new Error('Unexpected protocol command: ' + method);
  });
  manager.setLifecycleGuards({ canRelease: () => true, canCloseTarget: () => false });
  return { calls, closed, ownershipAtClose };
}

const homeSettings = { startupUrl: 'https://chatgpt.com/' };

test('approved idle homepage closes under process proof without fabricating target creation', async t => {
  const { manager } = await makeManager(t, homeSettings);
  await manager.ensureStarted();
  const child = manager.child, protocol = approvedHomeProtocol(t, manager);
  assert.equal(manager.isOwnedTarget('startup'), false);
  assert.equal((await manager.releaseIfUnused()).released, true);
  assert.deepEqual(protocol.closed, ['startup']);
  assert.deepEqual(protocol.ownershipAtClose, [false]);
  assert.equal(fs.existsSync(`/proc/${child.pid}`), false);
});

test('approved idle homepage remains eligible after manager restart with exact durable process proof', async t => {
  const { manager, config, managers } = await makeManager(t, homeSettings);
  await manager.ensureStarted();
  const restored = new ChromiumManager(config); managers.push(restored);
  const protocol = approvedHomeProtocol(t, restored);
  assert.equal(restored.isOwnedTarget('startup'), false);
  assert.equal((await restored.releaseIfUnused()).released, true);
  assert.deepEqual(protocol.closed, ['startup']);
});

test('periodic idle cleanup releases approved home-only browser without an owned-target outbox', async t => {
  const { manager } = await makeManager(t, homeSettings);
  await manager.ensureStarted();
  const protocol = approvedHomeProtocol(t, manager);
  await manager.closeIdleTargets();
  assert.deepEqual(protocol.closed, ['startup']);
  assert.equal(await manager.listExistingTargets(), null);
});

for (const url of ['https://chatgpt.com', 'https://chatgpt.com/?q=manual', 'https://chatgpt.com/#manual',
  'https://chatgpt.com/c/manual', 'https://chatgpt.com/g/project', 'https://chat.openai.com/', 'about:blank']) {
  test('approved-home exception does not broaden exact URL scope: ' + url, async t => {
    const { manager } = await makeManager(t, { startupUrl: url });
    await manager.ensureStarted();
    const protocol = approvedHomeProtocol(t, manager);
    assert.equal((await manager.releaseIfUnused()).released, false);
    assert.deepEqual(protocol.closed, []);
    assert.equal((await manager.listExistingTargets()).length, 1);
  });
}

test('approved home remains untouched in an unproven external browser with matching profile and port', async t => {
  const { manager, config, children } = await makeManager(t, homeSettings);
  await externalBrowser(config, children); await manager.ensureStarted();
  const protocol = approvedHomeProtocol(t, manager);
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.deepEqual(protocol.closed, []);
});

test('approved home requires an installed synchronous idle lifecycle guard', async t => {
  const { manager } = await makeManager(t, homeSettings);
  await manager.ensureStarted();
  const protocol = approvedHomeProtocol(t, manager);
  for (const canRelease of [undefined, () => false, async () => true]) {
    manager.setLifecycleGuards({ canRelease });
    assert.equal((await manager.releaseIfUnused({ guard: () => true })).released, false);
  }
  assert.deepEqual(protocol.closed, []);
});

for (const targetInfo of [{ browserContextId: 'other-context' }, { browserContextId: 'unknown-context' },
  { browserContextId: undefined }, { attached: true }, { type: 'service_worker' }, { targetId: 'reused-id' },
  { url: 'https://chatgpt.com/c/new-generation' }, { url: 'https://example.test/manual' }]) {
  test('approved-home final target check preserves changed or other-context target: ' + JSON.stringify(targetInfo), async t => {
    const { manager } = await makeManager(t, homeSettings);
    await manager.ensureStarted();
    const protocol = approvedHomeProtocol(t, manager, { targetInfo });
    assert.equal((await manager.releaseIfUnused()).released, false);
    assert.deepEqual(protocol.closed, []);
    assert.equal((await manager.listExistingTargets()).length, 1);
  });
}

test('approved-home cleanup preserves unknown default browser context instead of assuming ownership', async t => {
  const { manager } = await makeManager(t, homeSettings);
  await manager.ensureStarted();
  const protocol = approvedHomeProtocol(t, manager, { contexts: { browserContextIds: [] } });
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.deepEqual(protocol.closed, []);
});

for (const field of ['pid', 'startTime', 'bootId', 'port', 'profile', 'browserKey']) {
  test('approved-home close revalidates process ' + field + ' after target read', async t => {
    const { manager } = await makeManager(t, homeSettings);
    await manager.ensureStarted();
    const protocol = approvedHomeProtocol(t, manager, { onTargetInfo() { manager.ownership.process[field] = 'changed'; } });
    assert.equal((await manager.releaseIfUnused()).released, false);
    assert.deepEqual(protocol.closed, []);
    assert.equal((await manager.listExistingTargets()).length, 1);
  });
}

for (const phase of ['onConnect', 'onContexts', 'onTargetInfo']) {
  test('approved-home close preserves newly pending app work during ' + phase, async t => {
    const { manager } = await makeManager(t, homeSettings);
    await manager.ensureStarted();
    let idle = true;
    const protocol = approvedHomeProtocol(t, manager, { [phase]() { idle = false; } });
    manager.setLifecycleGuards({ canRelease: () => idle });
    assert.equal((await manager.releaseIfUnused()).released, false);
    assert.deepEqual(protocol.closed, []);
  });
}

test('approved-home close preserves concurrent target reuse even without an activity-file write', async t => {
  const { manager, config } = await makeManager(t, homeSettings);
  await manager.ensureStarted();
  const open = fs.openSync.bind(fs);
  t.mock.method(fs, 'openSync', (file, ...args) => {
    if (String(file).startsWith(config.browserActivityDir + path.sep)) throw new Error('activity write unavailable');
    return open(file, ...args);
  });
  const protocol = approvedHomeProtocol(t, manager, { onTargetInfo() { manager.touchTarget('startup'); } });
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.deepEqual(protocol.closed, []);
});

test('approved-home cleanup preserves a changed browser-instance key before any close command', async t => {
  const { manager } = await makeManager(t, homeSettings);
  await manager.ensureStarted();
  const protocol = approvedHomeProtocol(t, manager);
  const fetchOriginal = globalThis.fetch;
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    const response = await fetchOriginal(url, options);
    if (String(url).endsWith('/json/version')) {
      const version = await response.json();
      return Response.json({ ...version, webSocketDebuggerUrl: version.webSocketDebuggerUrl + '-changed' });
    }
    return response;
  });
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.deepEqual(protocol.closed, []);
});

test('approved-home release preserves an empty other browser context', async t => {
  const { manager } = await makeManager(t, homeSettings);
  await manager.ensureStarted();
  const protocol = approvedHomeProtocol(t, manager, {
    contexts: { browserContextIds: ['empty-other-context'], defaultBrowserContextId: 'default-context' },
  });
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.deepEqual(protocol.closed, []);
  assert.equal(fs.existsSync(`/proc/${manager.child.pid}`), true);
});

test('approved-home release preserves a context created after the home is closed', async t => {
  const { manager } = await makeManager(t, homeSettings);
  await manager.ensureStarted();
  const contexts = { browserContextIds: [], defaultBrowserContextId: 'default-context' };
  const protocol = approvedHomeProtocol(t, manager, { contexts });
  const list = manager.listExistingTargets.bind(manager);
  manager.listExistingTargets = async (...args) => {
    const targets = await list(...args);
    if (!targets.length) contexts.browserContextIds.push('new-empty-context');
    return targets;
  };
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.deepEqual(protocol.closed, ['startup']);
  assert.equal(fs.existsSync(`/proc/${manager.child.pid}`), true);
});


test('approved-home context preservation survives later empty-target sweeps and manager restart', async t => {
  const { manager, config, managers } = await makeManager(t, homeSettings);
  await manager.ensureStarted();
  const contexts = { browserContextIds: [], defaultBrowserContextId: 'default-context' };
  const protocol = approvedHomeProtocol(t, manager, { contexts });
  const list = manager.listExistingTargets.bind(manager);
  manager.listExistingTargets = async (...args) => {
    const targets = await list(...args);
    if (!targets.length) contexts.browserContextIds = ['new-empty-context'];
    return targets;
  };
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.deepEqual(protocol.closed, ['startup']);
  assert.equal((await manager.releaseIfUnused()).released, false);
  const restored = new ChromiumManager(config); managers.push(restored);
  approvedHomeProtocol(t, restored, { contexts });
  assert.equal((await restored.releaseIfUnused()).released, false);
  assert.equal(fs.existsSync(`/proc/${manager.child.pid}`), true);
  contexts.browserContextIds = [];
  assert.equal((await restored.releaseIfUnused()).released, true);
});


test('approved-home release preserves a manual target created during final context lookup', async t => {
  const { manager } = await makeManager(t, homeSettings);
  await manager.ensureStarted();
  let contextReads = 0;
  const protocol = approvedHomeProtocol(t, manager, { async onContexts() {
    if (++contextReads === 2) await fetch(manager.baseUrl + '/json/new?' + encodeURIComponent('https://example.test/manual'), { method: 'PUT' });
  } });
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.deepEqual(protocol.closed, ['startup']);
  assert.equal(fs.existsSync(`/proc/${manager.child.pid}`), true);
  assert.equal((await manager.listExistingTargets())[0].url, 'https://example.test/manual');
});

test('approved-home close stops if the durable context fence cannot be saved', async t => {
  const { manager } = await makeManager(t, homeSettings);
  await manager.ensureStarted();
  const protocol = approvedHomeProtocol(t, manager);
  const rename = fs.renameSync.bind(fs);
  t.mock.method(fs, 'renameSync', (...args) => {
    if (args[1] === manager.ownershipFile) throw new Error('fence persistence unavailable');
    return rename(...args);
  });
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.deepEqual(protocol.closed, []);
  assert.equal((await manager.listExistingTargets()).length, 1);
  assert.equal(JSON.parse(fs.readFileSync(manager.ownershipFile, 'utf8')).process.idleHomeContext, undefined);
});

test('restored empty home-fenced process waits for a synchronous lifecycle guard', async t => {
  const { manager, config, managers } = await makeManager(t, homeSettings);
  await manager.ensureStarted();
  let reads = 0, idle = true;
  const protocol = approvedHomeProtocol(t, manager, { onContexts() { if (++reads === 2) idle = false; } });
  manager.setLifecycleGuards({ canRelease: () => idle });
  assert.equal((await manager.releaseIfUnused()).released, false);
  assert.deepEqual(protocol.closed, ['startup']);
  const restored = new ChromiumManager(config); managers.push(restored);
  assert.equal((await restored.releaseIfUnused({ guard: () => true })).released, false);
  restored.setLifecycleGuards({ canRelease: async () => true });
  assert.equal((await restored.releaseIfUnused()).released, false);
  restored.setLifecycleGuards({ canRelease: () => true });
  assert.equal((await restored.releaseIfUnused()).released, true);
});

test('accepted close is pending while the owned target is still present', async (t) => {
  const { manager } = await makeManager(t, { closeKeepsTarget: true });
  const target = await manager.createTarget('https://example.test/still-present');
  const outcome = await manager.closeOwnedTarget(target.id);
  assert.deepEqual(outcome, { closed: false, absent: false, reason: 'target_still_present' });
  assert.equal(manager.isOwnedTarget(target.id), true);
  assert.equal((await manager.listExistingTargets()).some((row) => row.id === target.id), true);
});

test('accepted close without readable absence evidence remains pending and owned', async (t) => {
  const { manager } = await makeManager(t, { listUnavailableAfterClose: true });
  const target = await manager.createTarget('https://example.test/unverified');
  const outcome = await manager.closeOwnedTarget(target.id);
  assert.deepEqual(outcome, { closed: false, absent: false, reason: 'close_unverified' });
  assert.equal(manager.isOwnedTarget(target.id), true);
});

test('failed close response preserves the owned target for a later cleanup attempt', async (t) => {
  const { manager } = await makeManager(t, { closeFails: true });
  const target = await manager.createTarget('https://example.test/close-failed');
  const outcome = await manager.closeOwnedTarget(target.id);
  assert.deepEqual(outcome, { closed: false, absent: false, reason: 'close_failed' });
  assert.equal(manager.isOwnedTarget(target.id), true);
  assert.equal((await manager.listExistingTargets()).some((row) => row.id === target.id), true);
});

test('verified absence reports an authorized close even if lifecycle advances after its side effect', async (t) => {
  const { manager } = await makeManager(t);
  const target = await manager.createTarget('https://example.test/finished');
  const list = manager.listExistingTargets.bind(manager);
  let current = true;
  manager.listExistingTargets = async () => {
    const targets = await list();
    if (Array.isArray(targets) && !targets.some((row) => row.id === target.id)) current = false;
    return targets;
  };
  const outcome = await manager.closeOwnedTarget(target.id, { guard: () => current });
  assert.deepEqual(outcome, { closed: true, absent: false, reason: 'closed' });
  assert.equal(current, false);
  assert.equal(manager.isOwnedTarget(target.id), false);
});
