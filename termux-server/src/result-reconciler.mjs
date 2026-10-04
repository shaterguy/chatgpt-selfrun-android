const unavailable=(code,message)=>({state:'UNAVAILABLE',code,message});
export async function readResult(transport,record,documentId=record.body.result_document_id,predecessor=false) {
  if(!documentId||typeof transport.readGoogleDocText!=='function')
    return unavailable('RESULT_UNAVAILABLE','Result document or reader is unavailable; retry pending');
  let raw;
  try {raw=await transport.readGoogleDocText(documentId);}
  catch {return unavailable('RESULT_READ_FAILED','Result document could not be read; retry pending');}
  try {
    if(typeof raw!=='string'||raw.includes('\0')) throw new Error('Invalid Result text');
    const value=JSON.parse(raw.replace(/^\uFEFF/,''));
    const turn=Number(String(value.turn_id||'').split(':turn:')[1]);
    if(value.schema!=='selfrun-turn-result-v3'||value.task_id!==record.task_id||value.document_id!==documentId
      ||!Number.isSafeInteger(turn)||turn<1||value.turn!==turn||value.event_id!==value.turn_id+':result'
      ||typeof value.committed!=='boolean'||(predecessor?value.turn_id!==record.task_id+':turn:'+(Number(record.turn_id.split(':turn:')[1])-1):value.turn_id!==record.turn_id))
      throw new Error('Result identity/schema mismatch');
    if(!value.committed) return {state:'NOT_COMMITTED'};
  } catch {return unavailable('RESULT_READBACK_INVALID','Result contents or identity could not be verified; retry pending');}
  // Exact second read guards against a partially replaced or changing Result.
  let check;
  try {check=await transport.readGoogleDocText(documentId);}
  catch {return unavailable('RESULT_READ_FAILED','Result document could not be read; retry pending');}
  if(check!==raw)return unavailable('RESULT_READBACK_INVALID','Result changed during exact readback; retry pending');
  return {state:'COMMITTED'};
}
