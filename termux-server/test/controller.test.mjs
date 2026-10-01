import test from 'node:test';
import assert from 'node:assert/strict';
import { SelfRunController, buildPrompt, normalizeSignal } from '../src/controller.mjs';
import { SignalDispatchTransport } from '../src/signal-transport.mjs';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';
function setup(){
 const f=fixture();const transport=new SignalDispatchTransport(f.transport,f.store);
 f.http=new SelfRunController({lifecycle:f.controller,transport,stateStore:f.store,config:{}});
 f.signal=(turn=1)=>({signalId:'start-'+turn,type:turn===1?'START':'NEXT',projectUrl:f.body.project_url,
 envelope:{TASK_ID:f.body.task_id,TURN_ID:'SAFE-TEST:turn:'+turn,REQUEST_ID:'SAFE-TEST:turn:'+turn+'-request',
 SELF_RUN_SKILL_DOCUMENT_ID:'skill',RESULT_DOCUMENT_ID:turn===1?'RESULT-1':'RESULT-2',REQUIREMENT_DOCUMENT_ID:'requirement',
 ...(turn>1?{PREVIOUS_RESULT_DOCUMENT_ID:'RESULT-1'}:{})}});
 return f;
}
test('HTTP minimal envelope excludes noncontract informational fields',()=>{
 const f=setup(),signal=f.signal();signal.envelope.PHASE='ignored';
 assert.doesNotMatch(buildPrompt(normalizeSignal(signal,{})),/PHASE=/);
});
test('HTTP duplicate signal and process reconstruction preserve one logical input',async()=>{
 const f=setup();await f.http.accept(f.signal());await f.http.accept(f.signal());assert.equal(f.sends,1);
 await f.controller.quiesceForStandby();f.controller=f.recreate();
 f.http=new SelfRunController({lifecycle:f.controller,transport:new SignalDispatchTransport(f.transport,f.store),stateStore:f.store,config:{}});
 assert.equal((await f.http.accept(f.signal())).duplicate,true);assert.equal(f.sends,1);
});
test('HTTP NEXT forwards predecessor metadata as opaque prompt data',()=>{
 const f=setup();const signal=normalizeSignal(f.signal(2),{});
 const prompt=buildPrompt(signal);
 assert.match(prompt,/PREVIOUS_RESULT_DOCUMENT_ID=RESULT-1/);
});
test('HTTP unscoped STOP is rejected',async()=>{
 const f=setup();await f.http.accept(f.signal());
 await assert.rejects(f.http.accept({signalId:'stop',type:'STOP'}),/requires/);assert.equal(f.record().control_state,'RUNNING');
});
test('HTTP STOP preempts an in-flight POST before POST promise completes',async()=>{
 const f=setup(),entered=deferred(),gate=deferred();let signal;
 f.onSubmit=async args=>{signal=args.signal;entered.resolve();await gate.promise;};
 const posting=f.http.accept(f.signal());await entered.promise;
 try{
 await Promise.race([f.http.accept({signalId:'stop',type:'STOP',task_id:f.body.task_id,turn_id:f.body.turn_id,
 request_id:f.body.request_id,control_epoch:2}),new Promise((_,reject)=>setTimeout(()=>reject(new Error('STOP blocked behind POST')),500))]);
 assert.equal(signal.aborted,true);assert.equal(f.record().state,'STOPPED');
 }finally{gate.resolve();await posting;}
});
test('HTTP stale epoch cannot stop resumed execution',async()=>{
 const f=setup();await f.http.accept(f.signal());
 const base={task_id:f.body.task_id,turn_id:f.body.turn_id,request_id:f.body.request_id};
 await f.http.accept({...base,signalId:'stop',type:'STOP',control_epoch:2});
 await f.http.accept({...base,signalId:'resume',type:'RESUME',control_epoch:3});
 await assert.rejects(f.http.accept({...base,signalId:'late-stop',type:'STOP',control_epoch:2}),/stale/);
 assert.equal(f.record().control_state,'RUNNING');assert.equal(f.sends,1);
});

test('STANDBY HTTP resume persists control but cannot attach or submit',async()=>{
 const f=setup();await f.http.accept(f.signal());
 const base={task_id:f.body.task_id,turn_id:f.body.turn_id,request_id:f.body.request_id};
 await f.http.accept({...base,signalId:'stop',type:'STOP',control_epoch:2});
 f.controller.canDispatch=()=>false;await f.controller.quiesceForStandby();
 await f.http.accept({...base,signalId:'resume',type:'RESUME',control_epoch:3});
 assert.equal(f.resumes,0);assert.equal(f.sends,1);assert.equal(f.controller.standby,true);
});
