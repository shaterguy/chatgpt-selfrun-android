import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { PROMPT_DIRECTIVES_SCHEMA, PromptDirectiveStore, appendTurnStartDirective, humanizeSelfRunPrompt } from '../src/prompt-directives.mjs';

test('SelfRun machine envelope is rendered as a natural user instruction',()=>{
  const raw=[
    'TASK_ID=SR-TEST','TURN_ID=SR-TEST:turn:1','REQUEST_ID=SR-TEST:turn:1-request',
    'SELF_RUN_SKILL_DOCUMENT_ID=skill-doc','RESULT_DOCUMENT_ID=result-doc','REQUIREMENT_DOCUMENT_ID=requirement-doc',
    'PREVIOUS_RESULT_DOCUMENT_ID=previous-doc','',
    '[사용자 추가 지시 원문]','파일도 확인해',
  ].join('\n');
  const text=humanizeSelfRunPrompt(raw);
  assert.match(text,/SelfRun 작업을 진행해/);
  assert.match(text,/작업 ID는 SR-TEST/);
  assert.match(text,/SelfRun 운영문서 skill-doc/);
  assert.match(text,/요구사항 문서 requirement-doc/);
  assert.match(text,/이번 결과 문서 result-doc/);
  assert.match(text,/이전 결과 문서는 previous-doc/);
  assert.match(text,/추가 지시는 다음과 같아/);
  assert.match(text,/파일도 확인해/);
  assert.doesNotMatch(text,/TASK_ID=/);
  assert.doesNotMatch(text,/TURN_ID=/);
  assert.doesNotMatch(text,/\[SelfRun 서버 실행 지시\]/);
  assert.doesNotMatch(text,/@drive/);
});
test('turn start directive is appended as a plain natural sentence',()=>{
  const prompt=appendTurnStartDirective('SelfRun 작업을 진행해.','실질 작업을 수행해');
  assert.equal(prompt,'SelfRun 작업을 진행해.\n\n실질 작업을 수행해');
});
test('prompt directives reload from disk without restart and keep last valid config on malformed writes', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'selfrun-prompt-directives-'));
  const file = path.join(dir, 'prompt-directives.json');
  const store = new PromptDirectiveStore({promptDirectivesFile:file,turnStartDirective:'fallback start',recoveryPrompt:'fallback continue'});
  let current = await store.current();
  assert.equal(current.source, 'DEFAULT');
  assert.equal(current.turnStartDirective, 'fallback start');
  assert.equal(current.turnContinueDirective, 'fallback continue');
  await fs.writeFile(file, JSON.stringify({schema:PROMPT_DIRECTIVES_SCHEMA,turn_start_directive:'start v1',turn_continue_directive:'continue v1'}));
  current = await store.current();
  assert.equal(current.source, 'FILE');
  assert.equal(current.turnStartDirective, 'start v1');
  assert.equal(current.turnContinueDirective, 'continue v1');
  await fs.writeFile(file, JSON.stringify({schema:PROMPT_DIRECTIVES_SCHEMA,turn_start_directive:'start v2',turn_continue_directive:'continue v2'}));
  current = await store.current();
  assert.equal(current.turnStartDirective, 'start v2');
  await fs.writeFile(file, '{broken json');
  current = await store.current();
  assert.equal(current.source, 'LAST_VALID');
  assert.match(current.error, /JSON/);
  assert.equal(current.turnStartDirective, 'start v2');
  await fs.rm(file);
  current = await store.current();
  assert.equal(current.source, 'DEFAULT');
  assert.equal(current.turnStartDirective, 'fallback start');
  await fs.rm(dir,{recursive:true,force:true});
});
