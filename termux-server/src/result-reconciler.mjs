export async function readResult(transport,record,documentId=record.body.result_document_id,predecessor=false) {
  if(!documentId||typeof transport.readGoogleDocText!=='function') return {state:'UNAVAILABLE',code:'RESULT_UNAVAILABLE'};
  try {
    const raw=await transport.readGoogleDocText(documentId);
    if(raw.includes('\0')) throw new Error('NUL in Result');
    const value=JSON.parse(raw.replace(/^\uFEFF/,''));
    const turn=Number(String(value.turn_id||'').split(':turn:')[1]);
    if(value.schema!=='selfrun-turn-result-v3'||value.task_id!==record.task_id||value.document_id!==documentId
      ||!Number.isSafeInteger(turn)||turn<1||value.turn!==turn||value.event_id!==value.turn_id+':result'
      ||typeof value.committed!=='boolean'||(predecessor?value.turn_id!==record.task_id+':turn:'+(Number(record.turn_id.split(':turn:')[1])-1):value.turn_id!==record.turn_id))
      throw new Error('Result identity/schema mismatch');
    if(!value.committed) return {state:'NOT_COMMITTED'};
    // Exact second read guards against a partially replaced or changing Result.
    const check=await transport.readGoogleDocText(documentId);
    if(check!==raw) throw new Error('Result changed during exact readback');
    return {state:'COMMITTED'};
  } catch {return {state:'UNAVAILABLE',code:'RESULT_READBACK_INVALID'};}
}
