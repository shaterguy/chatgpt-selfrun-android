import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {constants} from 'node:fs';

const bashProbe = spawnSync('bash', ['--noprofile', '--norc', '-c', 'printf "%s" "$BASH"'],
  {encoding: 'utf8', timeout: 1500, env: {...process.env, BASH_ENV: ''}});
assert.equal(bashProbe.status, 0, 'an existing Bash executable is required for launcher fixtures');
const bash = await fs.realpath(bashProbe.stdout);
await fs.access(bash, constants.X_OK);
assert.equal(path.isAbsolute(bash) && !/[\s\0]/.test(bash), true, 'Bash must have a safe absolute shebang path');
const quote = value => "'" + String(value).replace(/'/g, "'\\''") + "'";
const shebang = '#!' + bash + '\n';

for (const script of ['20-selfrun-server.sh', 'selfrun-server-supervisor.sh']) {
  for (const mode of ['missing', 'failed', 'ready']) {
    test(`${script} shared service ${mode} gates SelfRun startup`, async t => {
      const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'selfrun-auth-launch-'));
      t.after(() => fs.rm(dir, {recursive: true, force: true}));
      const bin = path.join(dir, 'bin');
      const events = path.join(dir, 'events');
      const runtimeBin = path.join(dir, 'runtime-bin');
      await fs.mkdir(runtimeBin, {recursive: true});
      // This tool exists only on the inherited runtime PATH, like Termux tools.
      await fs.writeFile(path.join(runtimeBin, 'portable-system-tool'), shebang + 'exit 0\n', {mode: 0o700});
      const runtimePath = [runtimeBin, process.env.PATH].filter(Boolean).join(path.delimiter);
      await fs.mkdir(bin, {recursive: true});
      await fs.mkdir(path.join(dir, 'work/selfrun-termux-server/termux-server'), {recursive: true});
      const writeExecutable = (name, text) => fs.writeFile(path.join(bin, name), shebang + text, {mode: 0o700});
      if (mode !== 'missing') await writeExecutable('termux-google-auth-start.sh', `portable-system-tool || exit 1\necho auth >> ${quote(events)}\nexit ${mode === 'ready' ? 0 : 1}\n`);
      await writeExecutable('termux-wake-lock', 'exit 0\n');
      await writeExecutable('tmux', `if [ "$1" = has-session ]; then exit 1; fi\necho selfrun >> ${quote(events)}\n`);
      await writeExecutable('node', `echo selfrun >> ${quote(events)}\nkill -TERM "$PPID"\n`);
      assert.equal((await fs.readFile(path.join(bin, 'node'), 'utf8')).split('\n')[0], '#!' + bash);
      const original = await fs.readFile(new URL('../deploy/' + script, import.meta.url), 'utf8');
      const runnable = original.replace('export PREFIX=/data/data/com.termux/files/usr', `export PREFIX=${quote(dir)}`)
        .replace('export HOME=/data/data/com.termux/files/home', `export HOME=${quote(dir)}`)
        .replace('export PATH="$PREFIX/bin:/system/bin"', `export PATH=${quote([bin, path.dirname(bash), runtimePath].join(path.delimiter))}`);
      const runfile = path.join(dir, script);
      await fs.writeFile(runfile, runnable);
      const run = spawnSync(bash, ['--noprofile', '--norc', runfile],
        {timeout: 1500, encoding: 'utf8', env: {...process.env, BASH_ENV: '', PATH: runtimePath}});
      const calls = (await fs.readFile(events, 'utf8').catch(() => '')).trim().split('\n').filter(Boolean);
      if (mode === 'ready') assert.deepEqual(calls, ['auth', 'selfrun']);
      else {
        assert.equal(calls.includes('selfrun'), false);
        assert.equal(run.status, 1);
      }
    });
  }
}
