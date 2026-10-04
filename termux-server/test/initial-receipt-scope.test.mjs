import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './helpers/lifecycle-fixture.mjs';

function captureReadbacks(f) {
 const calls=[],original=f.browser.readSubmission;
 f.browser.readSubmission=async args=>{calls.push(args);return original(args);};
 return calls;
}

test('initial reconciliation explicitly enables fresh persisted-message readback',async()=>{
 const f=fixture(),calls=captureReadbacks(f);
 await f.ingest();
 assert.equal(calls.length,1);
 assert.equal(calls[0].intent.kind,'initial');
 assert.equal(calls[0].initialReadback,true);
 assert.equal(f.sends,1);
});

test('stall recovery retains current snapshot reacquisition without enabling initial readback',async()=>{
 const f=fixture(),calls=captureReadbacks(f),snapshots=[];
 const original=f.browser.livenessSnapshot;
 f.browser.livenessSnapshot=async args=>{const value=await original(args);snapshots.push(value);return value;};
 await f.existing();
 f.probe.userMessageId='external-device-user';
 f.probe.responseTurnId='external-device-response';
 await f.stall();
 assert.equal(calls.length,1);
 assert.equal(calls[0].intent.kind,'recovery');
 assert.equal(calls[0].initialReadback,false);
 assert.ok(snapshots.length>=2);
 assert.ok(snapshots.every(p=>p.userMessageId==='external-device-user'&&p.composer));
 assert.equal(f.resumes,1,'fresh snapshots do not reconnect the existing conversation');
 assert.equal(f.sends,1);
});

test('STOP with an uncertain initial intent does not enable the reconciliation-only readback',async()=>{
 const f=fixture(),calls=captureReadbacks(f);f.mode='unknown';
 await f.ingest();
 assert.equal(f.record().conversation_url,'');
 await f.stop();
 assert.equal(calls.length,2);
 assert.equal(calls[0].initialReadback,true);
 assert.equal(calls[1].intent.kind,'initial');
 assert.equal(calls[1].initialReadback,undefined);
 assert.equal(f.sends,1);
});
