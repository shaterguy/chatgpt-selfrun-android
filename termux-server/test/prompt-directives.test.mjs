import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {
  PROMPT_DIRECTIVES_SCHEMA,
  PromptDirectiveStore,
  appendTurnStartDirective,
} from '../src/prompt-directives.mjs';

test('turn start directive is appended after the canonical envelope', () => {
  const prompt = appendTurnStartDirective('TASK_ID=SR-TEST\nTURN_ID=SR-TEST:turn:1', '실질 작업을 수행해');
  assert.equal(prompt,
    'TASK_ID=SR-TEST\nTURN_ID=SR-TEST:turn:1'
    + '\n\n[SelfRun 서버 실행 지시]\n실질 작업을 수행해');
});

test('prompt directives reload from disk without restart and keep last valid config on malformed writes', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'selfrun-prompt-directives-'));
  const file = path.join(dir, 'prompt-directives.json');
  const store = new PromptDirectiveStore({
    promptDirectivesFile: file,
    turnStartDirective: 'fallback start',
    recoveryPrompt: 'fallback continue',
  });

  let current = await store.current();
  assert.equal(current.source, 'DEFAULT');
  assert.equal(current.turnStartDirective, 'fallback start');
  assert.equal(current.turnContinueDirective, 'fallback continue');

  await fs.writeFile(file, JSON.stringify({
    schema: PROMPT_DIRECTIVES_SCHEMA,
    turn_start_directive: 'start v1',
    turn_continue_directive: 'continue v1',
  }));
  current = await store.current();
  assert.equal(current.source, 'FILE');
  assert.equal(current.turnStartDirective, 'start v1');
  assert.equal(current.turnContinueDirective, 'continue v1');

  await fs.writeFile(file, JSON.stringify({
    schema: PROMPT_DIRECTIVES_SCHEMA,
    turn_start_directive: 'start v2',
    turn_continue_directive: 'continue v2',
  }));
  current = await store.current();
  assert.equal(current.source, 'FILE');
  assert.equal(current.turnStartDirective, 'start v2');
  assert.equal(current.turnContinueDirective, 'continue v2');

  await fs.writeFile(file, '{broken json');
  current = await store.current();
  assert.equal(current.source, 'LAST_VALID');
  assert.match(current.error, /JSON/);
  assert.equal(current.turnStartDirective, 'start v2');
  assert.equal(current.turnContinueDirective, 'continue v2');

  await fs.rm(file);
  current = await store.current();
  assert.equal(current.source, 'DEFAULT');
  assert.equal(current.turnStartDirective, 'fallback start');
  assert.equal(current.turnContinueDirective, 'fallback continue');
});
