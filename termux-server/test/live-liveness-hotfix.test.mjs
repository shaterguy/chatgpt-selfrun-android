import test from 'node:test';
import assert from 'node:assert/strict';
import { conversationStateReady } from '../src/browser/chatgpt.mjs';

test('response text makes a conversation structurally ready when legacy assistant id is absent', () => {
  assert.equal(conversationStateReady({
    userMessageId: 'user-1',
    assistantMessageId: null,
    streaming: false,
    paused: false,
    responseTextLength: 12,
  }), true);
});

test('empty response state does not become ready solely from a user message', () => {
  assert.equal(conversationStateReady({
    userMessageId: 'user-1',
    assistantMessageId: null,
    streaming: false,
    paused: false,
    responseTextLength: 0,
  }), false);
});
