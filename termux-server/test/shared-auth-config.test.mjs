import test from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';

for (const [specific, shared, expected] of [
  ['/selfrun.sock', '/common.sock', '/selfrun.sock'],
  ['', '/common.sock', '/common.sock'],
]) {
  test(`shared service socket config uses ${specific ? 'SelfRun override' : 'common override'}`, () => {
    const result = execFileSync(process.execPath, ['--input-type=module', '-e',
      'import {config} from "./src/config.mjs"; console.log(config.driveAuthServiceSocket);'], {
      cwd: new URL('..', import.meta.url), encoding: 'utf8',
      env: {...process.env, SELFRUN_DRIVE_AUTH_SERVICE_SOCKET: specific, TERMUX_GOOGLE_AUTH_SOCKET: shared},
    });
    assert.equal(result.trim(), expected);
  });
}
