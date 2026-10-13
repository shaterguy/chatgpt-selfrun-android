import test from 'node:test';
import assert from 'node:assert/strict';
import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';

test('new turn preparation ignores the old tab and always creates a fresh tab',async()=>{
  const created=[];
  const chromium={
    createTarget:async url=>{created.push(url);return{id:'fresh-tab'};},
    listExistingTargets:async()=>{throw Error('old tab must not be inspected');},
    connectTarget:async()=>{throw Error('intentional test interruption');},
  };
  const browser=new ChatGptBrowser(chromium,{});
  await assert.rejects(browser.prepare({
    projectUrl:'https://chatgpt.com/g/project',previousTargetId:'old-tab',
  }),/intentional test interruption/);
  assert.deepEqual(created,['about:blank']);
});

for(const mode of ['prepare','resume'])test(mode+' closes a newly created target when durable ownership callback fails',async()=>{
  let closed=0,connected=0;
  const chromium={listExistingTargets:async()=>[],createTarget:async()=>({id:'new-owned'}),
    connectTarget:async()=>{connected++;throw Error('should not connect');},closeTarget:async id=>{assert.equal(id,'new-owned');closed++;}};
  const browser=new ChatGptBrowser(chromium,{});
  await assert.rejects(browser[mode]({projectUrl:'https://chatgpt.com/',conversationUrl:'https://chatgpt.com/c/owned',
    onTarget:async()=>{throw Error('durable write failed');}}),/durable write failed/);
  assert.equal(connected,0);assert.equal(closed,1);
});
test('resume does not connect or close a manual target after STOP aborts its ownership callback',async()=>{
  let closed=0,connected=0;const abort=new AbortController();
  const chromium={listExistingTargets:async()=>[{id:'manual',type:'page',url:'https://chatgpt.com/c/owned'}],
    connectTarget:async()=>{connected++;throw Error('should not connect');},closeTarget:async()=>{closed++;}};
  const browser=new ChatGptBrowser(chromium,{});
  await assert.rejects(browser.resume({conversationUrl:'https://chatgpt.com/c/owned',signal:abort.signal,
    onTarget:async()=>{abort.abort(Error('app STOP'));}}),/app STOP/);
  assert.equal(connected,0);assert.equal(closed,0);
});
test('new-target failure cleanup forwards the exact current request guard',async()=>{
  let received;const guard=()=>false;
  const chromium={listExistingTargets:async()=>[],createTarget:async()=>({id:'new-owned'}),
    closeTarget:async(_id,options)=>{received=options?.guard;return false;}};
  const browser=new ChatGptBrowser(chromium,{});
  await assert.rejects(browser.resume({conversationUrl:'https://chatgpt.com/c/owned',guard,
    onTarget:async()=>{throw Error('retired');}}),/retired/);
  assert.equal(received,guard);
});
