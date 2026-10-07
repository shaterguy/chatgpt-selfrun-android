import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

for (const script of ['20-selfrun-server.sh', 'selfrun-server-supervisor.sh']) {
  for (const mode of ['missing', 'failed', 'ready']) {
    test(`${script} shared service ${mode} gates SelfRun startup`, async t => {
      const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'selfrun-auth-launch-'));
      t.after(() => fs.rm(dir, {recursive: true, force: true}));
      const bin = path.join(dir, 'bin');
      const events = path.join(dir, 'events');
      await fs.mkdir(bin, {recursive: true});
      await fs.mkdir(path.join(dir, 'work/selfrun-termux-server/termux-server'), {recursive: true});
      const writeExecutable = (name, text) => fs.writeFile(path.join(bin, name), '#!/bin/bash\n' + text, {mode: 0o700});
      if (mode !== 'missing') await writeExecutable('termux-google-auth-start.sh', `echo auth >> '${events}'\nexit ${mode === 'ready' ? 0 : 1}\n`);
      await writeExecutable('termux-wake-lock', 'exit 0\n');
      await writeExecutable('tmux', `if [ "$1" = has-session ]; then exit 1; fi\necho selfrun >> '${events}'\n`);
      await writeExecutable('node', `echo selfrun >> '${events}'\nkill -TERM "$PPID"\n`);
      const original = await fs.readFile(new URL('../deploy/' + script, import.meta.url), 'utf8');
      const runnable = original.replace('export PREFIX=/data/data/com.termux/files/usr', `export PREFIX=${dir}`)
        .replace('export HOME=/data/data/com.termux/files/home', `export HOME=${dir}`)
        .replace('export PATH="$PREFIX/bin:/system/bin"', 'export PATH="$PREFIX/bin:/usr/bin:/bin"');
      const runfile = path.join(dir, script);
      await fs.writeFile(runfile, runnable);
      const run = spawnSync('bash', [runfile], {timeout: 1500, encoding: 'utf8'});
      const calls = (await fs.readFile(events, 'utf8').catch(() => '')).trim().split('\n').filter(Boolean);
      if (mode === 'ready') assert.deepEqual(calls, ['auth', 'selfrun']);
      else {
        assert.equal(calls.includes('selfrun'), false);
        assert.equal(run.status, 1);
      }
    });
  }
}
