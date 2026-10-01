const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
export class DriveDispatchWatcher {
  constructor({transport,controller,config,canDispatch=null}) {
    Object.assign(this,{transport,controller,config});
    this.canDispatch=canDispatch||(()=>true);this.running=false;this.scanning=false;this.seen=new Map();
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
      const files=await this.transport.list();
      const controls=files.filter(f=>f.path.startsWith('__SELFRUN_CONTROL__'));
      const dispatches=files.filter(f=>f.path.startsWith('__SELFRUN_DISPATCH__'));
      for(const file of controls) {
        if(!this.canDispatch())return;
        const fingerprint=file.modTime+'|'+file.size;
        if(this.seen.get(file.path)===fingerprint)continue;
        const body=await this.transport.read(file.path);
        await this.controller.control(file.path,body,this.transport);
        this.seen.set(file.path,fingerprint);
      }
      for(const file of dispatches) {
        if(!this.canDispatch())return;
        const fingerprint=file.modTime+'|'+file.size;
        if(this.seen.get(file.path)===fingerprint)continue;
        const body=await this.transport.read(file.path);
        if(!this.canDispatch())return;
        if(body.schema!=='selfrun-server-dispatch-v1')continue;
        const control=this.controller.controlForTask(body.task_id);
        // A durable request can be reconciled while stopped; it cannot produce browser side effects.
        if(!control||control.turn_id!==body.turn_id||control.request_id!==body.request_id)continue;
        await this.controller.ingest(file.path,body,this.transport);
        this.seen.set(file.path,fingerprint);
      }
      if(this.canDispatch())await this.controller.tick(this.transport);
    } finally {this.scanning=false;}
  }
}
