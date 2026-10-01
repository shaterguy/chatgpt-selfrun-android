import test from 'node:test';
import assert from 'node:assert/strict';
import { effectiveStopButtonVisible } from '../src/browser/chatgpt.mjs';

test('empty project landing page ignores unrelated visible stop control',()=>{
  assert.equal(effectiveStopButtonVisible({
    rawStopButtonVisible:true,inConversation:false,userCount:0,assistantCount:0,responseTurnId:null,
  }),false);
});

test('stop control remains active once conversation context exists',()=>{
  assert.equal(effectiveStopButtonVisible({
    rawStopButtonVisible:true,inConversation:true,userCount:0,assistantCount:0,responseTurnId:null,
  }),true);
  assert.equal(effectiveStopButtonVisible({
    rawStopButtonVisible:true,inConversation:false,userCount:1,assistantCount:0,responseTurnId:null,
  }),true);
});
