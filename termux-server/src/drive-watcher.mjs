import { inputFingerprint } from './retired-requests.mjs';
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const fingerprint=file=>file?file.modTime+'|'+file.size:null;
const controlToken=control=>JSON.stringify(control?[control.task_id,control.turn_id,control.request_id,control.state,control.control_epoch]:null);
const recent=(file,cutoff)=>{
  const raw=String(file.modTime||'');
  if(!/^\d{4}-\d{2}-\d{2}T/.test(raw))return true;
  const ms=Date.parse(raw);
  return !Number.isFinite(ms)||ms>=cutoff;
};
export class DriveDispatchWatcher {
  constructor({transport,controller,config,canDispatch=null}) {
    Object.assign(this,{transport,controller,config});
    this.canDispatch=canDispatch||(()=>true);this.running=false;this.scanning=false;this.ticking=false;this.seen=new Map();this.inputs=new Map();this.deferred=new Map();
  }
  async start() {
    if(this.running)return;this.running=true;
    while(this.running) {
      try {await this.scanOnce();}
      catch(error){console.error(JSON.stringify({event:'DRIVE_WATCH_ERROR',error:String(error?.message||error).slice(0,300)}));}
      if(this.running)await delay(Math.max(500,Number(this.config.drivePollMs||1000)));
    }
  }
  stop(){this.running=false;}
  async scanOnce() {
    if(this.scanning||!this.canDispatch())return;
    this.scanning=true;
    try {
      const cutoff=Date.now()-Math.max(0,Number(this.config.dispatchRecoveryMs||7200000));
      const files=(await this.transport.list()).filter(file=>recent(file,cutoff));
      const listedControls=new Map(files.filter(f=>f.path.startsWith('__SELFRUN_CONTROL__')).map(f=>[f.path,f]));
      const present=new Set(files.map(f=>f.path));
      for(const path of this.deferred.keys())if(!present.has(path))this.deferred.delete(path);
      for(const file of files.toSorted((a,b)=>Number(b.path.startsWith('__SELFRUN_CONTROL__'))-Number(a.path.startsWith('__SELFRUN_CONTROL__')))) {
        if(!this.canDispatch())return;
        const fp=fingerprint(file);
        if(this.seen.get(file.path)===fp)continue;
        if(file.path.startsWith('__SELFRUN_CONTROL__')) {
          const body=await this.transport.read(file.path);
          await (this.controller.controlDurable?.(file.path,body,this.transport)??this.controller.control(file.path,body,this.transport));
          this.seen.set(file.path,fp);
          continue;
        }
        if(!file.path.startsWith('__SELFRUN_DISPATCH__'))continue;
        if(this.controller.retiredPath?.(file.path)){this.seen.set(file.path,fp);continue;}
        let deferred=this.deferred.get(file.path);
        if(deferred?.fp!==fp){this.deferred.delete(file.path);deferred=null;}
        if(deferred&&deferred.controlFp===fingerprint(listedControls.get(deferred.controlPath))
          &&deferred.controlToken===controlToken(this.controller.controlForTask(deferred.taskId)))continue;
        const body=await this.transport.read(file.path);
        if(!this.canDispatch())return;
        if(body.schema!=='selfrun-server-dispatch-v1'){this.seen.set(file.path,fp);continue;}
        const controlPath='__SELFRUN_CONTROL__'+body.task_id+'.json';
        let control=this.controller.controlForTask(body.task_id);
        const controlFile=listedControls.get(controlPath);
        const controlFp=controlFile?fingerprint(controlFile):null;
        const controlListedUnseen=controlFile&&this.seen.get(controlPath)!==controlFp;
        const identityMismatch=!control||control.turn_id!==body.turn_id||control.request_id!==body.request_id;
        if(controlListedUnseen||(identityMismatch&&!controlFile)) {
          let latest;
          try {latest=await this.transport.read(controlPath);} catch(error) {
            if(error?.code!=='DRIVE_FILE_NOT_FOUND')throw error;
          }
          if(latest)await (this.controller.controlDurable?.(controlPath,latest,this.transport)??this.controller.control(controlPath,latest,this.transport));
          if(controlFile)this.seen.set(controlPath,controlFp);
          control=this.controller.controlForTask(body.task_id);
        }
        if(control&&control.state!=='UNKNOWN'&&(control.turn_id!==body.turn_id||control.request_id!==body.request_id)) {
          // Reconsider either signal when it changes, without rereading unchanged historical files on every poll.
          this.deferred.set(file.path,{fp,taskId:body.task_id,controlPath,controlFp,controlToken:controlToken(control)});
          continue;
        }
        this.deferred.delete(file.path);
        const input=inputFingerprint(body);
        if(this.inputs.get(file.path)===input){this.seen.set(file.path,fp);continue;}
        await (this.controller.ingestDurable?.(file.path,body,this.transport)??this.controller.ingest(file.path,body,this.transport));
        this.inputs.set(file.path,input);
        this.seen.set(file.path,fp);
      }
      if(this.canDispatch()&&!this.ticking) {
        this.ticking=true;
        void this.controller.tick(this.transport).catch(error=>{
          console.error(JSON.stringify({event:'DRIVE_WATCH_TICK_ERROR',error:String(error?.message||error).slice(0,300)}));
        }).finally(()=>{this.ticking=false;});
      }
    } finally {this.scanning=false;}
  }
}
