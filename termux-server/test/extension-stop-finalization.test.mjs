import test from 'node:test';import assert from 'node:assert/strict';
import {clearedExitedExtensionJournal} from '../src/browser/extension-manager.mjs';
const proof={pid:42,startTime:'123',bootId:'boot'};
const journal=()=>({schema:'selfrun-extension-ownership-v1',ownerId:'owner',profile:'/profile',browserSessionId:'session',process:{...proof},targets:[],targetMetadata:{},controllerStopPending:true,retiringProcess:{...proof},unrelated:{preserve:true}});
const absent={processIdentity:()=>null,processExists:()=>false,bootId:()=> 'boot'};
test('clear only completed stop fields and preserve original journal and process proof',()=>{const j=journal(),before=structuredClone(j),next=clearedExitedExtensionJournal(j,absent,()=>true);assert.deepEqual(j,before);assert.deepEqual(next,{...before,controllerStopPending:false,retiringProcess:null});});
for(const mode of ['alive','unreadable','unknown-existence','different-retiring-process'])test('preserve completed-stop candidates when '+mode+' is not proven exited',()=>{
 const j=journal(),deps={...absent};
 if(mode==='alive')Object.assign(deps,{processIdentity:()=>({...proof,state:'S'}),processExists:()=>true});
 if(mode==='reused')Object.assign(deps,{processIdentity:()=>({...proof,startTime:'new',state:'S'}),processExists:()=>true});
 if(mode==='unreadable')deps.processExists=()=>true;
 if(mode==='unknown-existence')deps.processExists=()=>undefined;
 if(mode==='wrong-boot-zombie')Object.assign(deps,{processIdentity:()=>({...proof,state:'Z'}),processExists:()=>true,bootId:()=> 'other'});
 if(mode==='different-retiring-process'){j.retiringProcess.pid=43;Object.assign(deps,{processIdentity:pid=>pid===43?{...proof,pid:43,state:'S'}:null,processExists:pid=>pid===43});}
 assert.equal(clearedExitedExtensionJournal(j,deps,()=>true),null);assert.equal(j.controllerStopPending,true);assert.ok(j.retiringProcess);
});
for(const mode of ['target','metadata','work','missing-proof','invalid-schema'])test('stop finalization refuses '+mode,()=>{
 const j=journal();if(mode==='target')j.targets=['owned'];if(mode==='metadata')j.targetMetadata.orphan={};if(mode==='missing-proof')j.process=null;if(mode==='invalid-schema')j.schema='unknown';
 assert.equal(clearedExitedExtensionJournal(j,absent,()=>mode!=='work'),null);assert.equal(j.controllerStopPending,true);
});
test('matching exited zombie is allowed without requiring or sending a signal',()=>{const next=clearedExitedExtensionJournal(journal(),{...absent,processIdentity:()=>({...proof,state:'Z'}),processExists:()=>true},()=>true);assert.equal(next.controllerStopPending,false);});
test('new work discovered during proof sampling leaves journal intact',()=>{let allowed=true;const j=journal(),deps={...absent,processExists:()=>{allowed=false;return false;}};assert.equal(clearedExitedExtensionJournal(j,deps,()=>allowed),null);assert.equal(j.controllerStopPending,true);});

for(const mode of ['reused','wrong-boot-zombie'])test('stale owner '+mode+' is proven exited without signalling its replacement',()=>{
 const j=journal(),deps={...absent};
 if(mode==='reused')Object.assign(deps,{processIdentity:()=>({...proof,startTime:'new',state:'S'}),processExists:()=>true});
 else Object.assign(deps,{processIdentity:()=>({...proof,state:'Z'}),processExists:()=>true,bootId:()=> 'other'});
 const next=clearedExitedExtensionJournal(j,deps,()=>true);assert.ok(next);assert.equal(next.controllerStopPending,false);assert.equal(next.retiringProcess,null);assert.equal(j.controllerStopPending,true);
});
