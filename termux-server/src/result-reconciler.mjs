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
  const text=raw.replace(/^\uFEFF/,'');let value,recovered=false;
  try {value=JSON.parse(text);}
  catch {
    // Matches the current app's SelfRun3ResultDocumentPolicy: only one final
    // redundant brace, and only an otherwise valid committed Result. Never write a repair.
    const trimmed=text.trim(),candidate=trimmed.endsWith('}')?trimmed.slice(0,-1).trim():'';
    try {
      if(!candidate.endsWith('}'))throw new Error();
      value=JSON.parse(candidate);recovered=true;
    } catch {return unavailable('RESULT_JSON_INVALID','Result JSON could not be parsed; retry pending');}
  }
  if(!identityMatches(value,record,documentId,predecessor))
    return unavailable('RESULT_IDENTITY_MISMATCH','Result identity or schema does not match; retry pending');
  if(recovered&&!value.committed)
    return unavailable('RESULT_JSON_INVALID','Result JSON could not be parsed; retry pending');
  if(!value.committed)return {state:'NOT_COMMITTED'};
  // Exact raw second read also fences the bounded recovery against changing documents.
  let check;
  try {check=await transport.readGoogleDocText(documentId);}
  catch {return unavailable('RESULT_READ_FAILED','Result document could not be read; retry pending');}
  if(check!==raw)return unavailable('RESULT_READBACK_CHANGED','Result changed during exact readback; retry pending');
  return {state:'COMMITTED',...(recovered?{recovery:'SINGLE_TRAILING_BRACE'}:{})};
}
