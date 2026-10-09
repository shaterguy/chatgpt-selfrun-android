import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './helpers/lifecycle-fixture.mjs';

for(const readyAt of ['new-watch','new-stop'])test('pending old owned target survives same-request different-URL watch and cleans at '+readyAt,async t=>{
 const f=fixture(),oldUrl=f.probe.url,newUrl='https://chatgpt.com/c/new-owned-watch';
 const pages=new Map(),closed=[];let oldReady=false;
 f.browser.chromium={isOwnedTarget:id=>pages.has(id),closeOwnedTarget:async(id,{guard})=>{
  const url=pages.get(id);if(!guard({id,url}))return {closed:false,reason:'guard_rejected'};
  if(id==='old-owned'&&!oldReady)return {closed:false,reason:'temporary_unavailable'};
  if(!pages.delete(id))return {absent:true};closed.push(id);return {closed:true};
 },releaseIfUnused:async()=>({released:false})};
 f.browser.resume=async args=>{
  f.resumes++;const id=args.conversationUrl===oldUrl?'old-owned':'new-owned';pages.set(id,args.conversationUrl);
  await args.onTarget?.({id});return {target:{id},session:{close(){}},baseline:{...f.probe,url:args.conversationUrl}};
 };
 f.controller=f.recreate();t.after(()=>f.controller.quiesceForStandby());
 await f.existing();await f.stop();await f.controller.cleanupTerminalResources();
 assert.equal(pages.has('old-owned'),true);assert.ok(f.controller.repository.data.browserCleanup['old-owned']);
 f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:newUrl};
 await f.controller.control(null,f.control,f.transport);await f.controller.tick(f.transport);await f.settle();
 assert.equal(f.record().state,'OBSERVING');assert.equal(f.record().conversation_url,newUrl);
 assert.equal(f.resumes,2);assert.equal(f.prepares,0);assert.equal(f.sends,0);assert.equal(pages.has('new-owned'),true);
 assert.deepEqual(f.store.snapshot().lifecycle.retired,{});assert.deepEqual(f.store.snapshot().lifecycle.browserCleanup,{});
 if(readyAt==='new-watch'){
  oldReady=true;await f.controller.cleanupTerminalResources();assert.deepEqual(closed,['old-owned']);
  assert.equal(pages.has('new-owned'),true);assert.equal(f.record().state,'OBSERVING');
 }
 await f.stop();await f.controller.cleanupTerminalResources();
 assert.equal(pages.has('new-owned'),false);
 oldReady=true;await f.controller.cleanupTerminalResources();await f.controller.cleanupTerminalResources();
 assert.deepEqual([...closed].sort(),['new-owned','old-owned']);assert.equal(pages.size,0);
 assert.equal(f.controller.repository.resourceCleanups.size,0);assert.equal(f.controller.browserCleanup.controls.size,0);
 assert.equal(f.sends,0);assert.equal(f.resumes,2);assert.equal(f.record(),null);
 assert.deepEqual(f.store.snapshot().lifecycle.retired,{});assert.deepEqual(f.store.snapshot().lifecycle.browserCleanup,{});
});

for(const claim of ['same-url','repurposed-url'])test('independent pending resource preserves '+claim+' ownership fence',async t=>{
 const f=fixture(),oldUrl=f.probe.url,newUrl='https://chatgpt.com/c/other-owned';
 const pages=new Map(),closed=[];let ready=false;
 f.browser.chromium={isOwnedTarget:id=>pages.has(id),closeOwnedTarget:async(id,{guard})=>{
  if(!pages.has(id))return {absent:true};if(!ready||!guard({id,url:pages.get(id)}))return {closed:false};
  pages.delete(id);closed.push(id);return {closed:true};
 },releaseIfUnused:async()=>({released:false})};
 f.browser.resume=async args=>{
  f.resumes++;const id=args.conversationUrl===oldUrl?'old':'new';pages.set(id,args.conversationUrl);
  await args.onTarget?.({id});return {target:{id},session:{close(){}},baseline:{...f.probe,url:args.conversationUrl}};
 };
 f.controller=f.recreate();t.after(()=>f.controller.quiesceForStandby());await f.existing();await f.stop();await f.controller.cleanupTerminalResources();
 f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:claim==='same-url'?oldUrl:newUrl};
 await f.controller.control(null,f.control,f.transport);await f.controller.tick(f.transport);
 if(claim==='repurposed-url')pages.set('old','https://example.test/manual');
 ready=true;await f.controller.cleanupTerminalResources();assert.deepEqual(closed,[]);assert.equal(f.record().state,'OBSERVING');
 await f.stop();await f.controller.cleanupTerminalResources();
 assert.deepEqual(closed,claim==='same-url'?['old']:['new']);assert.equal(pages.has('old'),claim!=='same-url');
 assert.equal(f.controller.repository.resourceCleanups.size,claim==='same-url'?0:1);
 assert.equal(f.sends,0);assert.deepEqual(f.store.snapshot().lifecycle.retired,{});assert.deepEqual(f.store.snapshot().lifecycle.browserCleanup,{});
});
