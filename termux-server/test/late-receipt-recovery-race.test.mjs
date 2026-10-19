import test from 'node:test';import assert from 'node:assert/strict';import {fixture,deferred} from './helpers/lifecycle-fixture.mjs';import {keyFor} from '../src/lifecycle.mjs';
async function arrange(t,absence=false){
 let now=1700000000000;t.mock.method(Date,'now',()=>now);
 const f=fixture();f.mode=absence?'absent':'unknown';f.probe.url='https://chatgpt.com/c/WEB:00000000-0000-4000-8000-000000000001';
 const read=deferred();let reads=0;f.browser.readSubmission=()=>++reads===1?read.promise:Promise.resolve({state:'UNKNOWN',probe:{...f.probe}});
 t.after(()=>f.controller.quiesceForStandby());await f.ingest();f.mode='unknown';await f.observe({});
 now+=600001;await f.observe({status:'STALLED'});assert.equal(f.record().reason,'RECOVERY_URL_UNOBSERVED');
 return {f,read,settle:async()=>{await f.settle();await f.settle();}};
}
test('late unknown receipt after URL-unobserved recovery remains valid and never resends',async t=>{
 const {f,read,settle}=await arrange(t);read.resolve({state:'UNKNOWN',probe:{...f.probe}});await settle();
 assert.notEqual(f.record().error?.code,'OBSERVATION_CALLBACK_FAILED');assert.equal(f.record().state,'OBSERVING');
 assert.equal(f.record().intent.outcome,'UNKNOWN');assert.equal(f.sends,1);
});
test('late proven absence follows explicit reconcile phase and permits only the bounded original retry',async t=>{
 const {f,read,settle}=await arrange(t,true);read.resolve({state:'ABSENT',probe:{...f.probe}});await settle();
 assert.notEqual(f.record().error?.code,'OBSERVATION_CALLBACK_FAILED');assert.equal(f.sends,2);assert.equal(f.record().intent.sends,2);
 await f.controller.tick(f.transport);await settle();assert.equal(f.sends,2);
});
for(const action of ['STOP','new-generation','new-control'])test('late absence cannot send after '+action,async t=>{
 const {f,read,settle}=await arrange(t,true);
 if(action==='STOP')await f.stop();
 if(action==='new-generation')await f.controller.repository.move(keyFor(f.body),f.record().state,'test newer intent',{intent:{...f.record().intent,receipt_generation:'new-generation'}});
 if(action==='new-control'){f.control={...f.control,state:'WAITING_USER_INTERVENTION',control_epoch:2};await f.controller.control(null,f.control,f.transport);}
 read.resolve({state:'ABSENT',probe:{...f.probe}});await settle();assert.equal(f.sends,1);
 if(action==='STOP')assert.equal(f.record(),null);
 if(action==='new-generation')assert.equal(f.record().intent.receipt_generation,'new-generation');
});
