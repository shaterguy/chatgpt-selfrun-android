import { createHash } from 'node:crypto';

const DISPATCH_TYPES = new Set(['START', 'NEXT']);
const CONTROL_TYPES = new Set(['START', 'NEXT', 'PAUSE', 'STOP', 'RESUME']);
const ENVELOPE_KEYS = [
  'TASK_ID',
  'TURN_ID',
  'REQUEST_ID',
  'SELF_RUN_SKILL_DOCUMENT_ID',
  'RESULT_DOCUMENT_ID',
  'REQUIREMENT_DOCUMENT_ID',
  'PREVIOUS_RESULT_DOCUMENT_ID',
];
const REQUIRED_ENVELOPE_KEYS = ENVELOPE_KEYS.filter((key) => key !== 'PREVIOUS_RESULT_DOCUMENT_ID');

function cleanString(value) {
  return typeof value === 'string' ? value.trim() : '';
}

function normalizeProjectUrl(value) {
  const raw = cleanString(value);
  if (!raw) return '';
  const url = new URL(raw);
  if (url.protocol !== 'https:' || !['chatgpt.com', 'www.chatgpt.com'].includes(url.hostname)) {
    throw new Error('projectUrl must be an https://chatgpt.com URL');
  }
  return url.toString();
}

export function normalizeSignal(raw, config) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('Signal body must be an object');
  const signalId = cleanString(raw.signalId);
  const type = cleanString(raw.type).toUpperCase();
  if (!signalId) throw new Error('signalId is required');
  if (!CONTROL_TYPES.has(type)) throw new Error('Unsupported signal type');

  if (!DISPATCH_TYPES.has(type)) return { signalId, type };

  const envelope = {};
  for (const key of ENVELOPE_KEYS) {
    const value = cleanString(raw.envelope?.[key]);
    if (value) envelope[key] = value;
  }
  for (const key of REQUIRED_ENVELOPE_KEYS) {
    if (!envelope[key]) throw new Error(`envelope.${key} is required`);
  }

  const projectUrl = normalizeProjectUrl(raw.projectUrl || config.defaultProjectUrl);
  if (!projectUrl) throw new Error('projectUrl is required for START/NEXT');

  return {
    signalId,
    type,
    projectUrl,
    envelope,
    additionalInput: cleanString(raw.additionalInput),
    receivedAt: new Date().toISOString(),
  };
}

export function buildPrompt(signal) {
  const lines = [];
  for (const key of ENVELOPE_KEYS) {
    const value = signal.envelope?.[key];
    if (value) lines.push(`${key}=${value}`);
  }
  if (signal.additionalInput) {
    lines.push('', '[사용자 추가 지시 원문]', signal.additionalInput);
  }
  return lines.join('\n');
}

export class SelfRunController {
  constructor({lifecycle,transport,stateStore,config}) {
    Object.assign(this,{lifecycle,transport,stateStore,config});
    this.tail=Promise.resolve();
  }
  async initialize() {
    // Startup restoration is performed by the common watcher/lifecycle; no runtime reset.
  }
  accept(raw) {
    const signal=normalizeSignal(raw,this.config);
    const queue=DISPATCH_TYPES.has(signal.type)?'tail':'controlTail';
    const p=(this[queue]||Promise.resolve()).then(()=>this.#accept(raw),()=>this.#accept(raw));
    this[queue]=p.catch(()=>{});
    return p;
  }
  async #accept(raw) {
    const signal=normalizeSignal(raw,this.config);
    if(this.transport.local().signals[signal.signalId])return {accepted:true,duplicate:true,status:'RECORDED'};
    if(!DISPATCH_TYPES.has(signal.type)) {
      const task=cleanString(raw.task_id),turn=cleanString(raw.turn_id),request=cleanString(raw.request_id),epoch=Number(raw.control_epoch);
      if(!task||!turn||!request||!Number.isSafeInteger(epoch)||epoch<1)
        throw new Error('HTTP control requires task_id, turn_id, request_id and control_epoch');
      if(this.lifecycle.repository.records().some(r=>r.task_id===task&&r.body.server_ingress!=='HTTP'))throw new Error('task is owned by Drive ingress');
      const prior=this.lifecycle.controlForTask(task);
      if(!prior||prior.turn_id!==turn||prior.request_id!==request||epoch<=prior.control_epoch)
        throw new Error('stale or unscoped HTTP control');
      const control={schema:'selfrun-task-control-v1',task_id:task,turn_id:turn,request_id:request,
        control_epoch:epoch,state:signal.type==='STOP'?'STOPPED':signal.type==='RESUME'?'RUNNING':'PAUSED',updated_at_ms:Date.now()};
      const path='__SELFRUN_CONTROL__'+task+'.json';
      await this.transport.setControl(signal.signalId,path,control);
      await this.lifecycle.control(path,control,this.transport);
      if(signal.type==='RESUME')await this.lifecycle.tick(this.transport);
      return {accepted:true,duplicate:false,status:control.state};
    }
    const e=signal.envelope,task=e.TASK_ID,request=e.REQUEST_ID;
    const records=this.lifecycle.repository.records().filter(r=>r.task_id===task);
    if(records.some(r=>r.body.server_ingress!=='HTTP'))throw new Error('task is owned by Drive ingress');
    const existing=records.find(r=>r.request_id===request);
    const prior=this.lifecycle.controlForTask(task);
    if(existing&&prior?.state!=='RUNNING')throw new Error('explicit higher-epoch task RESUME required');
    const path='__SELFRUN_DISPATCH__HTTP_'+createHash('sha256').update(request).digest('hex')+'.json';
    const control={schema:'selfrun-task-control-v1',task_id:task,turn_id:e.TURN_ID,request_id:request,state:'RUNNING',
      control_epoch:Number(prior?.control_epoch||0)+1,updated_at_ms:Date.now()};
    const body=existing?.body||{schema:'selfrun-server-dispatch-v1',server_ingress:'HTTP',
      task_id:task,turn_id:e.TURN_ID,request_id:request,dispatch_attempt:1,project_url:signal.projectUrl,
      prompt:buildPrompt(signal),profile_operations:Array.isArray(raw.profile_operations)?raw.profile_operations:[],
      result_document_id:e.RESULT_DOCUMENT_ID,previous_result_document_id:e.PREVIOUS_RESULT_DOCUMENT_ID||'',
      client_status:'SEND_REQUESTED',server_status:'PENDING',server_control_epoch:control.control_epoch,created_at_ms:Date.now()};
    await this.transport.register(signal.signalId,path,body,'__SELFRUN_CONTROL__'+task+'.json',control);
    await this.lifecycle.control(null,control,this.transport);
    await this.lifecycle.ingest(path,body,this.transport);
    return {accepted:true,duplicate:false,status:this.lifecycle.getActive(path)?.serverStatus||'RECORDED'};
  }
}
