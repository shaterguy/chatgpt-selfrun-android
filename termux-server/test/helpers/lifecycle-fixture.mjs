import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { DriveDispatchController } from '../../src/drive-controller.mjs';
import { DriveDispatchWatcher } from '../../src/drive-watcher.mjs';
import { StateStore } from '../../src/state-store.mjs';
import { keyFor, transition } from '../../src/lifecycle.mjs';

class MemoryStore {
  constructor(value={generation:0}){this.value=structuredClone(value);this.events=[];}
  snapshot(){return structuredClone(this.value);}
  async patch(p){if(this.fail)throw new Error('disk failed');this.value={...this.value,...structuredClone(p)};return this.snapshot();}
  async update(fn){this.value=structuredClone(fn(this.snapshot()));return this.snapshot();}
  async recordEvent(event,details){this.events.push({event,details});}
}
const deferred=()=>{let resolve;const promise=new Promise(r=>{resolve=r;});return {promise,resolve};};
function fixture(options={}) {
  const f={sends:0,prepares:0,resumes:0,quiesces:0,readbacks:0,store:options.store||new MemoryStore(),observations:[],sent:[]};
  f.body={schema:'selfrun-server-dispatch-v1',task_id:'SAFE-TEST',turn_id:'SAFE-TEST:turn:1',request_id:'SAFE-TEST:turn:1-request',
    dispatch_attempt:1,project_url:'https://chatgpt.com/g/test/project',prompt:'test input',profile_operations:[],
    result_document_id:'RESULT-1',client_status:'CREATE_REQUESTED',server_status:'PENDING',server_control_epoch:1};
  f.path='__SELFRUN_DISPATCH__test.json';
  f.control={schema:'selfrun-task-control-v1',task_id:f.body.task_id,turn_id:f.body.turn_id,request_id:f.body.request_id,state:'RUNNING',control_epoch:1};
  f.docs=new Map();
  f.result=(committed=false,id='RESULT-1',turn=1)=>f.docs.set(id,JSON.stringify({schema:'selfrun-turn-result-v3',task_id:'SAFE-TEST',turn_id:'SAFE-TEST:turn:'+turn,
    turn,document_id:id,event_id:'SAFE-TEST:turn:'+turn+':result',committed}));
  f.result();
  f.probe={url:'https://chatgpt.com/c/test-owned',composer:true,readyState:'complete',streaming:true,stopButtonVisible:true,paused:false,
    assistantCount:1,assistantTextLength:955,assistantMessageId:'a1',responseTurnId:'response-1',responseTextLength:955,responseFingerprint:'fixture-fingerprint',
    userCount:1,userTextLength:10,userMessageId:'u1'};
  f.rows=new Map([[f.path,structuredClone(f.body)]]);
  f.transport={
    read:async name=>structuredClone(name.startsWith('__SELFRUN_CONTROL__')?f.control:f.rows.get(name)),
    write:async(name,body)=>{if(f.writeFail)throw new Error('Drive unavailable');f.rows.set(name,structuredClone(body));},
    readGoogleDocText:async id=>{if(!f.docs.has(id))throw new Error('missing');return f.docs.get(id);},
    list:async()=>[{path:'__SELFRUN_CONTROL__SAFE-TEST.json',modTime:String(f.control.control_epoch),size:10},
      ...[...f.rows].map(([p,b])=>({path:p,modTime:String(b.updated_at_ms||1),size:JSON.stringify(b).length}))],
  };
  const session=()=>({close(){}});
  f.browser={
    prepare:async()=>{f.prepares++;return {target:{id:'target-new'},session:session(),baseline:{...f.probe}};},
    resume:async()=>{f.resumes++;if(f.attachError)throw f.attachError;return {target:{id:'target-owned'},session:session(),baseline:{...f.probe}};},
    attachTarget:async()=>({target:{id:'target-new'},session:session(),baseline:{...f.probe}}),
    livenessSnapshot:async()=>({...f.probe}),
    quiesce:async()=>{f.quiesces++;f.probe.streaming=false;f.probe.stopButtonVisible=false;await f.onQuiesce?.();},
    submitIntent:async args=>{
      f.sends++;
      f.sent.push({prompt:args.prompt,kind:args.kind,profileOperations:structuredClone(args.profileOperations||[])});
      await args.onRequest({message_id:'message-'+f.sends,released:f.mode!=='absent'});
      if(f.publishConversationBeforeOutcome)await args.onConversation?.({url:f.probe.url,probe:{...f.probe}});
      await f.onSubmit?.(args);
      if(f.mode==='absent'&&f.sends===1)throw Object.assign(new Error('Canonical conversation POST timeout'),{released:false,absenceProof:'INTERCEPTED_REQUEST_ABORTED'});
      if(f.mode==='unknown')throw Object.assign(new Error('Canonical conversation POST timeout'),{released:true});
      f.probe.userCount++;f.probe.userMessageId='message-'+f.sends;f.probe.responseTurnId='response-'+f.sends;
      f.sentId='message-'+f.sends;
      if(f.mode==='accepted-timeout')throw Object.assign(new Error('Canonical conversation POST timeout'),{released:true});
      return {...f.probe};
    },
    readSubmission:async({intent})=>{
      f.readbacks++;
      if(f.sentId===intent.message_id)return {state:'CONFIRMED',messageId:f.sentId,probe:{...f.probe}};
      if(intent.absence_proof==='INTERCEPTED_REQUEST_ABORTED'&&!intent.released)return {state:'ABSENT',probe:{...f.probe}};
      return {state:'UNKNOWN',probe:{...f.probe}};
    },
    monitor:async args=>{f.observations.push(args);await new Promise(resolve=>args.signal.addEventListener('abort',resolve,{once:true}));return {status:'ABORTED'};},
  };
  const config={maxAttachAttempts:3,resumeRetryMs:1,stallAfterMs:1,recoveryPrompt:'continue test'};
  f.recreate=()=>new DriveDispatchController({browser:f.browser,stateStore:f.store,config});
  f.controller=f.recreate();
  f.record=()=>f.controller.repository.get(keyFor(f.body));
  f.ingest=async()=>{f.rows.set(f.path,structuredClone(f.body));await f.controller.ingest(f.path,f.body,f.transport);};
  f.existing=async()=>{Object.assign(f.body,{conversation_url:f.probe.url,client_status:'SEND_REQUESTED',server_status:'STARTED'});await f.ingest();};
  f.observe=async overrides=>{const a=f.observations.at(-1);assert.ok(a,'monitor attached');await a.onActivity({...f.probe,...overrides});};
  f.stall=async()=>{
    await f.observe({});
    await f.controller.repository.move(keyFor(f.body),'OBSERVING','test elapsed time',{last_progress_at:Date.now()-1000});
    await f.observe({status:'STALLED'});
  };
  f.stop=async()=>{f.control={...f.control,state:'STOPPED',control_epoch:f.control.control_epoch+1};await f.controller.control(null,f.control,f.transport);};
  f.resume=async()=>{f.control={...f.control,state:'RUNNING',control_epoch:f.control.control_epoch+1};await f.controller.control(null,f.control,f.transport);await f.controller.tick(f.transport);};
  return f;
}

export { MemoryStore, deferred, fixture };
