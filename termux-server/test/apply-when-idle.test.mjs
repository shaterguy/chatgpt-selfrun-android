import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';

const module = await import('../tools/apply-when-idle.mjs').catch(() => ({}));
const empty = () => ({generation: 7, status: 'IDLE', activeCount: 0, activeDispatches: [],
  lifecycle: {requests: {}, controls: {}, outbox: {}, completionOutbox: {}, browserCleanup: {}, browserCleanupUnresolved: {}},
  httpIngress: {files: {}, signals: {}}});
const sample = () => ({idle: true, generation: 7, fingerprint: 'same', node: {pid: 42, startTime: '9'}, owner: {}});
function environment(samples = [sample(), sample()]) {
  const calls = [];
  const deps = {
    inspect: async frozen => {calls.push(frozen ? 'inspect-frozen' : 'inspect'); return samples.shift();},
    pause: async () => calls.push('pause'), resume: async () => calls.push('resume'),
    verifyCandidate: async () => calls.push('verify'), retireBrowser: async () => calls.push('retire-browser'),
    assertFrozen: async () => calls.push('assert-frozen'), swap: async () => calls.push('swap'),
    rollback: async () => calls.push('rollback'), terminate: async () => calls.push('terminate'),
  };
  return {calls, deps};
}
test('idle patch requires every task and durable resource to be absent', () => {
  assert.equal(typeof module.idleSnapshot, 'function');
  assert.equal(module.idleSnapshot(empty()), true);
  assert.equal(module.idleSnapshot({...empty(),status:'ERROR'}), true);
  for (const change of [s => s.activeCount = 1, s => s.lifecycle.requests.x = {state:'BLOCKED'},
    s => s.lifecycle.controls.x = {state:'PAUSED'}, s => s.lifecycle.controls.x = {state:'WAITING_USER_INTERVENTION'},
    s => s.lifecycle.browserCleanup.x = {}, s => s.httpIngress.files.x = {}]) {
    const state = empty(); change(state); assert.equal(module.idleSnapshot(state), false);
  }
});
test('an active task is never paused or patched', async () => {
  assert.equal(typeof module.attemptIdlePatch, 'function');
  const {calls,deps} = environment([{idle:false,reason:'current_tasks'}]);
  assert.equal((await module.attemptIdlePatch(deps)).state, 'WAITING');
  assert.deepEqual(calls, ['inspect']);
});
test('a task arriving between the idle sample and freeze resumes the old server', async () => {
  const {calls,deps} = environment([sample(), {...sample(),idle:false,reason:'new_task'}]);
  assert.equal((await module.attemptIdlePatch(deps)).state, 'WAITING');
  assert.deepEqual(calls, ['inspect','pause','inspect-frozen','resume']);
});
test('a changed generation or Drive fingerprint postpones the patch', async () => {
  for(const changed of [{generation:8},{fingerprint:'new-control'}]) {
    const {calls,deps} = environment([sample(), {...sample(),...changed}]);
    assert.equal((await module.attemptIdlePatch(deps)).state, 'WAITING');
    assert.ok(calls.includes('resume')); assert.ok(!calls.includes('swap'));
  }
});
test('a frozen inspection failure always resumes the exact old process', async () => {
  const {calls,deps} = environment();
  deps.inspect = async frozen => {if(frozen)throw new Error('Drive unavailable');return sample();};
  await assert.rejects(module.attemptIdlePatch(deps), /Drive unavailable/);
  assert.deepEqual(calls, ['pause','resume']);
});
test('candidate verification failure cannot replace the launcher', async () => {
  const {calls,deps} = environment(); deps.verifyCandidate=async()=>{throw Error('source changed');};
  await assert.rejects(module.attemptIdlePatch(deps), /source changed/);
  assert.ok(calls.includes('resume')); assert.ok(!calls.includes('swap'));
});
test('only an unchanged empty frozen server hands off to the candidate', async () => {
  const {calls,deps} = environment();
  assert.equal((await module.attemptIdlePatch(deps)).state, 'SWITCHED');
  assert.deepEqual(calls,['inspect','pause','inspect-frozen','verify','retire-browser','assert-frozen','swap','assert-frozen','terminate']);
});
test('a termination failure restores the old launcher and resumes its process', async () => {
  const {calls,deps} = environment(); deps.terminate=async()=>{calls.push('terminate');throw Error('signal failed');};
  await assert.rejects(module.attemptIdlePatch(deps), /signal failed/);
  assert.deepEqual(calls.slice(-3), ['terminate','rollback','resume']);
});

test('Drive idle guard paginates the configured task folders and performs only reads', async () => {
  const calls=[];
  const response = body => ({ok:true,json:async()=>body});
  const fetcher = async (url, options) => {
    calls.push([url,options]); const u=new URL(url);
    if(u.pathname.endsWith('/files/control')) return response({schema:'selfrun-task-control-v1',task_id:'SR-X',state:'PAUSED'});
    const q=u.searchParams.get('q');
    if(q.includes("mimeType='application/vnd.google-apps.folder'")) {
      return u.searchParams.has('pageToken') ? response({files:[{id:'taskFolder',name:'SR-X'}]}) : response({files:[],nextPageToken:'folders2'});
    }
    assert.ok(q.includes("'runsRoot' in parents")); assert.ok(q.includes("'taskFolder' in parents"));
    return response({files:[{id:'control',name:'__SELFRUN_CONTROL__SR-X.json',modifiedTime:'now'}]});
  };
  assert.equal((await module.readDriveIdle({driveRunsFolderId:'runsRoot'},async()=>'fixture',fetcher)).idle,false);
  assert.equal(calls.length,4);
  assert.ok(calls.every(([,options])=>!options.method || options.method==='GET'));
});

test('an unavailable Drive read cannot authorize a patch', async () => {
  await assert.rejects(module.readDriveIdle({driveRunsFolderId:'runsRoot'},async()=>'fixture',async()=>({ok:false,status:503})),/HTTP 503/);
});

function dispatchReader(control, dispatch) {
  const respond=body=>({ok:true,json:async()=>body});
  return async url => {
    const u=new URL(url);
    if(u.pathname.endsWith('/control'))return respond(control);
    if(u.pathname.endsWith('/dispatch'))return respond(dispatch);
    if(u.searchParams.get('q').includes("mimeType='application/vnd.google-apps.folder'"))return respond({files:[]});
    return respond({files:[...(control?[{id:'control',name:'__SELFRUN_CONTROL__SR-X.json',version:'1'}]:[]),
      {id:'dispatch',name:'__SELFRUN_DISPATCH__SR-X:turn:2-request__A1.json',version:'1'}]});
  };
}
test('a pending dispatch without its app control keeps the patch waiting', async () => {
  const dispatch={schema:'selfrun-server-dispatch-v1',task_id:'SR-X',turn_id:'SR-X:turn:2',request_id:'request2',client_status:'SEND_REQUESTED'};
  assert.equal((await module.readDriveIdle({driveRunsFolderId:'runsRoot'},async()=>'fixture',dispatchReader(null,dispatch))).idle,false);
});
test('a successor dispatch cannot borrow the stopped predecessor control', async () => {
  const control={schema:'selfrun-task-control-v1',task_id:'SR-X',turn_id:'SR-X:turn:1',request_id:'request1',state:'STOPPED'};
  const dispatch={schema:'selfrun-server-dispatch-v1',task_id:'SR-X',turn_id:'SR-X:turn:2',request_id:'request2',client_status:'CREATE_REQUESTED'};
  assert.equal((await module.readDriveIdle({driveRunsFolderId:'runsRoot'},async()=>'fixture',dispatchReader(control,dispatch))).idle,false);
});
test('terminal app control retires its exact dispatch and previous protocol turns', async () => {
  const dispatch={schema:'selfrun-server-dispatch-v1',task_id:'SR-X',turn_id:'SR-X:turn:2',request_id:'request2',client_status:'SEND_REQUESTED'};
  for(const turn of [2,3]) {
    const control={schema:'selfrun-task-control-v1',task_id:'SR-X',turn_id:'SR-X:turn:'+turn,request_id:'request'+turn,state:'DONE'};
    assert.equal((await module.readDriveIdle({driveRunsFolderId:'runsRoot'},async()=>'fixture',dispatchReader(control,dispatch))).idle,true);
  }
});

test('resumeExact cannot signal a reused or mismatched process identity', async t => {
  if(!module.processIdentity(process.pid)) return t.skip('runtime virtualizes process IDs; run on the target Termux host');
  const child=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});
  const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  try {
    await delay(30); const proof=module.processIdentity(child.pid); assert.ok(proof);
    process.kill(child.pid,'SIGSTOP'); await delay(30);
    assert.ok(['T','t'].includes(module.processIdentity(child.pid).state));
    module.resumeExact({...proof,startTime:'different'}); await delay(30);
    assert.ok(['T','t'].includes(module.processIdentity(child.pid).state));
    module.resumeExact(proof); await delay(30);
    assert.ok(!['T','t'].includes(module.processIdentity(child.pid).state));
  } finally {child.kill('SIGKILL');}
});
