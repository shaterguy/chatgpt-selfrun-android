import fs from 'node:fs';
import readline from 'node:readline';
import { keyFor, newIntent } from './lifecycle.mjs';

export async function readLifecycleHistory(eventsFile) {
  const legacy=new Map(),audits=new Map();
  if(!eventsFile||!fs.existsSync(eventsFile))return {legacy,audits};
  const stream=fs.createReadStream(eventsFile,{encoding:'utf8'});
  const lines=readline.createInterface({input:stream,crlfDelay:Infinity});
  for await(const line of lines) {
    let row;try{row=JSON.parse(line);}catch{continue;}
    const d=row.details||{};
    if(!d.task_id||!d.turn_id||!d.request_id)continue;
    const key=keyFor(d);
    if(row.event==='CONVERSATION_TRANSITION') {
      audits.set(key,Math.max(audits.get(key)||0,Number(d.revision||0)));continue;
    }
    const old=legacy.get(key)||{recovery_count:0,unknown_post:false};
    const observed=d.current_cursor||d.cursor||d.stalled_cursor;
    if(observed)old.baseline={userCount:observed.user_count,userMessageId:observed.user_message_id,
      responseTurnId:observed.response_turn_id,responseTextLength:observed.response_text_length,responseFingerprint:observed.response_fingerprint};
    old.recovery_count=Math.max(old.recovery_count,Number(d.recovery_count||0));
    if(/Canonical conversation POST timeout|CDP command timeout: Runtime.evaluate/.test(String(d.error||''))) {
      old.unknown_post=true;old.error=String(d.error).slice(0,300);
    }
    if(row.event==='LIVENESS_RECOVERY_SENT')old.unknown_post=false;
    legacy.set(key,old);
  }
  return {legacy,audits};
}
export function migrateLegacy(record,history,prompt) {
  const old=history.legacy.get(record.key);
  if(!old)return record;
  record.recovery_count=Math.max(record.recovery_count,old.recovery_count);
  if(old.unknown_post&&!record.intent) {
    record.intent={...newIntent(record,'recovery',prompt,old.baseline||{}),outcome:'UNKNOWN',sends:1,legacy:true,released:true};
    record.state='POST_UNCERTAIN';
    record.error={code:'LEGACY_POST_UNCERTAIN',message:old.error};
    record.recovery_stage='LEGACY_POST_READBACK_REQUIRED';
  }
  return record;
}
