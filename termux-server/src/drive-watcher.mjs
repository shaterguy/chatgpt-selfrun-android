import { inputFingerprint } from './retired-requests.mjs';
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const fingerprint=file=>file?file.modTime+'|'+file.size:null;
const controlToken=control=>JSON.stringify(control?[control.task_id,control.turn_id,control.request_id,control.state,control.control_epoch,control.conversation_url||'']:null);
const isControl=file=>file.path.startsWith('__SELFRUN_CONTROL__');
const modified=file=>Number(file.modTime)||Date.parse(file.modTime)||0;
// App names locate a control; a fresh control body, not the name, grants admission.
const taskFor=path=>/^__SELFRUN_DISPATCH__(.+?):turn:\d+(?::|-)/.exec(path)?.[1]||null;
const matches=(path,c)=>{
  if(!c||c.schema!=='selfrun-task-control-v1'||c.state!=='RUNNING'||!Number.isSafeInteger(c.control_epoch)
    ||c.control_epoch<1||!c.task_id||!c.turn_id||!c.request_id||taskFor(path)!==c.task_id)return false;
  const prefix='__SELFRUN_DISPATCH__'+c.request_id.replace(/[^A-Za-z0-9._:-]/g,'_').slice(0,160)+'__A';
  return path.startsWith(prefix)&&/^\d+\.json$/.test(path.slice(prefix.length));
};
export class DriveDispatchWatcher {
  constructor({transport,controller,config,canDispatch=null}) {
    Object.assign(this,{transport,controller,config});
    this.canDispatch=canDispatch||(()=>true);this.running=false;this.scanning=false;this.ticking=false;this.stopped=false;
    this.seen=new Map();this.inputs=new Map();this.deferred=new Map();
    this.files=new Map();this.jobs=new Map();this.controls=new Map();this.reads=new Map();this.readFailures=new Map();this.priority=new Map();this.failures=new Map();
    this.lanes=new Set();this.listed=false;this.completedScans=0;
    this.runningControls=new Map();
  }
  status(){return {dispatchEnabled:!this.stopped&&this.canDispatch(),completedScans:this.completedScans,
    scanning:this.scanning,pendingFiles:this.jobs.size,pendingReads:this.reads.size,
    deferredFiles:this.deferred.size,failedFiles:this.failures.size};}
  async start() {
    if(this.running)return;this.running=true;this.stopped=false;
    while(this.running) {
      try {await this.scanOnce({drain:false});}
      catch(error){this.#error('DRIVE_WATCH_ERROR',error);}
      if(this.running)await delay(Math.max(500,Number(this.config.drivePollMs||1000)));
    }
  }
  stop(){this.running=false;this.stopped=true;this.runningControls.clear();}
  #error(event,error) {console.error(JSON.stringify({event,error:String(error?.message||error).slice(0,300)}));}
  #current(file) {return !this.stopped&&this.canDispatch()&&fingerprint(this.files.get(file.path))===fingerprint(file);}
  #tick() {
    if(this.stopped||!this.canDispatch()||this.ticking)return;
    this.ticking=true;
    void (async()=>{
      try {await this.controller.tick(this.transport);}
      catch(error){this.#error('DRIVE_WATCH_TICK_ERROR',error);}
      finally {this.ticking=false;}
    })();
  }
  #needed(file) {
    if(!isControl(file)&&!taskFor(file.path))return false;
    const fp=fingerprint(file),failed=this.failures.get(file.path);
    if(this.seen.get(file.path)===fp||this.jobs.has(file.path)||(failed?.fp===fp&&failed.retryAt>Date.now()))return false;
    const deferred=this.deferred.get(file.path);
    return !(deferred?.fp===fp&&deferred.controlFp===fingerprint(this.files.get(deferred.controlPath))
      &&deferred.controlToken===controlToken(this.controller.controlForTask(deferred.taskId)));
  }
  #drain() {
    if(this.stopped||!this.canDispatch())return;
    // One lane each for new controls, new dispatches, and historical discovery.
    // A slow old file cannot occupy either of the next-poll intake lanes.
    for(const lane of ['control','intake','discovery']) {
      if(this.lanes.has(lane))continue;
      const file=[...this.files.values()].find(file=>this.#needed(file)
        &&(this.priority.has(file.path)?(isControl(file)?'control':'intake'):'discovery')===lane);
      if(!file)continue;
      this.lanes.add(lane);
      const fp=fingerprint(file);
      const job=Promise.resolve().then(()=>this.#process(file)).then(()=>{
        this.failures.delete(file.path);
        if(this.seen.get(file.path)===fp&&this.priority.get(file.path)===fp)this.priority.delete(file.path);
      },error=>{
        const old=this.failures.get(file.path),attempts=old?.fp===fp?old.attempts+1:1;
        if(this.#current(file))this.failures.set(file.path,{fp,attempts,retryAt:Math.max(error.watchRetryAt||0,Date.now()+Math.min(60000,1000*2**Math.min(attempts-1,6)))});
        throw error;
      }).finally(()=>{
        this.jobs.delete(file.path);this.lanes.delete(lane);
        this.#drain();
      });
      this.jobs.set(file.path,job);
      void job.catch(error=>this.#error('DRIVE_WATCH_FILE_ERROR',error));
    }
  }
  async scanOnce({drain=true}={}) {
    this.#tick();
    if(this.scanning||this.stopped||!this.canDispatch())return;
    this.scanning=true;
    try {
      // Age only orders discovery. Current app control decides execution authority.
      const files=(await this.transport.list()).filter(file=>isControl(file)||taskFor(file.path))
        .toSorted((a,b)=>modified(b)-modified(a));
      if(this.stopped||!this.canDispatch())return;
      if(!this.listed) {
        // Start with the newest viable dispatch; it loads its own control before ingestion.
        const first=files.findIndex(file=>!isControl(file)&&!this.controller.retiredPath?.(file.path));
        if(first>0)files.unshift(...files.splice(first,1));
      }
      const activeTasks=new Set((this.controller.activeDispatches?.()||[]).map(a=>a.body?.task_id||a.task_id));
      for(const file of files) {
        const taskId=isControl(file)?file.path.slice('__SELFRUN_CONTROL__'.length,-'.json'.length):null;
        if((this.listed&&fingerprint(this.files.get(file.path))!==fingerprint(file))
          ||(taskId&&activeTasks.has(taskId)))this.priority.set(file.path,fingerprint(file));
      }
      this.files=new Map(files.map(file=>[file.path,file]));this.listed=true;
      for(const map of [this.seen,this.inputs,this.deferred,this.priority,this.failures,this.readFailures])for(const path of map.keys())if(!this.files.has(path))map.delete(path);
      for(const task of this.runningControls.keys())if(!this.files.has('__SELFRUN_CONTROL__'+task+'.json'))this.runningControls.delete(task);
      this.#drain();
      this.completedScans++;
    } finally {this.scanning=false;}
    // Explicit one-shot callers may wait for intake; production polling never does.
    if(drain)while(this.jobs.size)await Promise.all([...this.jobs.values()]);
  }
  #pace(path,fp,error,failed) {
    const attempts=failed?.fp===fp?failed.attempts+1:1;
    const retryAt=Date.now()+Math.min(60000,1000*2**Math.min(attempts-1,6));
    const paced=Object.assign(new Error(String(error?.message||error)),{code:error?.code,watchRetryAt:retryAt});
    if(this.files.has(path)||isControl({path}))this.readFailures.set(path,{fp,attempts,retryAt,error:paced});return paced;
  }
  #read(path) {
    if(this.reads.has(path))return this.reads.get(path);
    const fp=fingerprint(this.files.get(path)),failed=this.readFailures.get(path);
    if(failed?.fp===fp&&failed.retryAt>Date.now())return Promise.reject(failed.error);
    const job=Promise.resolve().then(()=>this.transport.read(path)).then(body=>{
      this.readFailures.delete(path);return body;
    },error=>{
      throw this.#pace(path,fp,error,failed);
    }).finally(()=>this.reads.delete(path));
    this.reads.set(path,job);return job;
  }
  #forget(path) {
    this.files.delete(path);
    for(const map of [this.seen,this.inputs,this.deferred,this.priority,this.failures,this.readFailures])map.delete(path);
  }
  #observeControl(path,c) {
    const task=path.slice('__SELFRUN_CONTROL__'.length,-'.json'.length);
    const changed=this.runningControls.get(task)!==controlToken(c);
    if(c.state==='RUNNING')this.runningControls.set(task,controlToken(c));
    else {this.runningControls.delete(task);this.#forget(path);}
    for(const file of [...this.files.values()])if(taskFor(file.path)===task) {
      if(!matches(file.path,c))this.#forget(file.path);
      else if(changed)for(const map of [this.seen,this.inputs,this.deferred,this.failures,this.readFailures])map.delete(file.path);
    }
  }
  async #control(file,unlisted=false) {
    if(this.controls.has(file.path))return this.controls.get(file.path);
    const fp=unlisted?null:fingerprint(file),failed=this.readFailures.get(file.path);
    const current=()=>unlisted?(!this.stopped&&this.canDispatch()&&!this.files.has(file.path)):this.#current(file);
    const job=(async()=>{
      const body=await this.#read(file.path);
      if(!current())return;
      if(body?.schema!=='selfrun-task-control-v1'||body.task_id!==file.path.slice('__SELFRUN_CONTROL__'.length,-'.json'.length)
        ||!Number.isSafeInteger(body.control_epoch)||body.control_epoch<1)throw new Error('invalid task control');
      await (this.controller.controlDurable?.(file.path,body,this.transport)??this.controller.control(file.path,body,this.transport));
      if(!current())return;
      this.#observeControl(file.path,body);
      if(!unlisted&&body.state==='RUNNING')this.seen.set(file.path,fp);
      return body;
    })().catch(error=>{
      if(error.watchRetryAt>Date.now())throw error;
      throw this.#pace(file.path,fp,error,failed);
    }).finally(()=>this.controls.delete(file.path));
    this.controls.set(file.path,job);return job;
  }
  async #process(file) {
    if(!this.#current(file))return;
    const fp=fingerprint(file);
    if(this.seen.get(file.path)===fp)return;
    if(isControl(file))return this.#control(file);
    const task=taskFor(file.path);
    if(!task){this.#forget(file.path);return;}
    const controlPath='__SELFRUN_CONTROL__'+task+'.json';
    const loadControl=()=>this.#control(this.files.get(controlPath)||{path:controlPath},!this.files.has(controlPath));
    let control;
    try{control=await loadControl();}catch(error){if(error?.code!=='DRIVE_FILE_NOT_FOUND')throw error;this.#forget(file.path);return;}
    if(!control)return;
    if(!matches(file.path,control)){this.#forget(file.path);return;}
    if(!this.#current(file))return;
    const body=await this.#read(file.path);
    if(!this.#current(file))return;
    if(body.schema!=='selfrun-server-dispatch-v1'){this.seen.set(file.path,fp);return;}
    // The app can STOP, resume or choose a successor while the body is read.
    control=await loadControl();
    if(!control||!this.#current(file))return;
    if(!matches(file.path,control)||control.task_id!==body.task_id||control.turn_id!==body.turn_id||control.request_id!==body.request_id){this.#forget(file.path);return;}
    const controlFp=fingerprint(this.files.get(controlPath));
    this.deferred.delete(file.path);
    const input=inputFingerprint(body);
    if(this.inputs.get(file.path)===input){this.seen.set(file.path,fp);return;}
    const accepted=await (this.controller.ingestDurable?.(file.path,body,this.transport)??this.controller.ingest(file.path,body,this.transport));
    if(!this.#current(file))return;
    if(accepted===null) {
      this.deferred.set(file.path,{fp,taskId:body.task_id,controlPath,controlFp,controlToken:controlToken(this.controller.controlForTask(body.task_id))});return;
    }
    if(accepted===undefined){this.inputs.delete(file.path);this.seen.set(file.path,fp);return;}
    this.inputs.set(file.path,input);this.seen.set(file.path,fp);
  }
}
