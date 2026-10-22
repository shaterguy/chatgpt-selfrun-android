const unavailable=(code,message)=>({state:'UNAVAILABLE',code,message});
const identityMatches=(value,record,documentId,predecessor)=>{
  if(!value||typeof value!=='object'||Array.isArray(value))return false;
  const turn=Number(String(value.turn_id||'').split(':turn:')[1]);
  return value.schema==='selfrun-turn-result-v3'&&value.task_id===record.task_id&&value.document_id===documentId
    &&Number.isSafeInteger(turn)&&turn>=1&&value.turn===turn&&value.event_id===value.turn_id+':result'
    &&typeof value.committed==='boolean'&&(predecessor?value.turn_id===record.task_id+':turn:'+(Number(record.turn_id.split(':turn:')[1])-1):value.turn_id===record.turn_id);
};
export async function readResult(transport,record,documentId=record.body.result_document_id,predecessor=false) {
  if(!documentId||typeof transport.readGoogleDocText!=='function')
    return unavailable('RESULT_UNAVAILABLE','Result document or reader is unavailable; retry pending');
  let raw;
  try {raw=await transport.readGoogleDocText(documentId);}
  catch {return unavailable('RESULT_READ_FAILED','Result document could not be read; retry pending');}
  if(typeof raw!=='string'||raw.includes('\0'))
    return unavailable('RESULT_TEXT_INVALID','Result text is invalid; retry pending');
  const text=raw.replace(/^\uFEFF/,'');let value;
  try {value=JSON.parse(text);}
  catch {return unavailable('RESULT_JSON_INVALID','Result JSON could not be parsed; retry pending');}
  if(!identityMatches(value,record,documentId,predecessor))
    return unavailable('RESULT_IDENTITY_MISMATCH','Result identity or schema does not match; retry pending');
  return {state:value.committed?'COMMITTED':'NOT_COMMITTED'};
}


// The only business-status read in the deterministic executor: the user-defined
// continuation exception. Unknown text never becomes an invented task status.
export async function readUserIntervention(transport,record,{signal}={}) {
  const id=record.body.result_document_id;
  if(!id||typeof transport.readGoogleDocText!=='function')return {state:'UNKNOWN'};
  const abort=signal?AbortSignal.any([signal,AbortSignal.timeout(30000)]):AbortSignal.timeout(30000);
  let onAbort;
  try {
    const raw=await Promise.race([transport.readGoogleDocText(id,{signal:abort}),new Promise((_,reject)=>{
      onAbort=()=>reject(abort.reason);if(abort.aborted)onAbort();else abort.addEventListener('abort',onAbort,{once:true});
    })]);
    if(typeof raw!=='string')return {state:'UNKNOWN'};
    let value;try{value=JSON.parse(raw.replace(/^\uFEFF/,''));}catch{return {state:'UNKNOWN'};}
    if(!identityMatches(value,record,id,false))return {state:'UNKNOWN'};
    return {state:value.status==='USER_ACTION_REQUIRED'?'INTERVENTION':'CONTINUE'};
  } catch(error) {
    if(signal?.aborted)throw signal.reason;
    return {state:'UNKNOWN',ioError:true};
  } finally {if(onAbort)abort.removeEventListener('abort',onAbort);}
}
