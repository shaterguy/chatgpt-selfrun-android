import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import {createHash, createHmac, randomUUID} from 'node:crypto';
import {execFile, spawn} from 'node:child_process';
import {promisify} from 'node:util';
import {pathToFileURL} from 'node:url';
import {EXTENSION_ID} from '../src/browser/extension-package.mjs';
import {attestController} from '../src/browser/server-action-session.mjs';

const exec = promisify(execFile);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const hash = value => createHash('sha256').update(value).digest('hex');
const object = value => value && typeof value === 'object' && !Array.isArray(value);
const emptyObject = value => object(value) && Object.keys(value).length === 0;
const terminalControl = value => ['STOPPED','DONE'].includes(value?.state);
export const idleBrowserTargets = targets => Array.isArray(targets)&&!targets.some(target=>target.type==='page'&&target.url!==`chrome-extension://${EXTENSION_ID}/controller.html`);

// A paused, blocked, or intervention task is still work. No task is retired here.
export function idleSnapshot(state) {
  const life = state?.lifecycle;
  return !!life && Number.isSafeInteger(state.generation)
    && state.activeCount === 0 && Array.isArray(state.activeDispatches) && !state.activeDispatches.length
    && !state.activeSignal && emptyObject(life.requests) && object(life.controls)
    && Object.values(life.controls).every(terminalControl)
    && ['outbox','completionOutbox','browserCleanup','browserCleanupUnresolved'].every(key => emptyObject(life[key] || {}))
    && (!state.httpIngress || emptyObject(state.httpIngress.files) && emptyObject(state.httpIngress.signals));
}

// The process boundary is injected so arrival/failure paths can be tested without
// signalling a production server. Real dependencies below perform exact PID checks.
export async function attemptIdlePatch(deps) {
  const before = await deps.inspect(false);
  if (!before.idle) return {state:'WAITING',reason:before.reason};
  let paused = false, swapped = false;
  try {
    await deps.pause(before.node); paused = true;
    const after = await deps.inspect(true, before.node);
    if (!after.idle || before.generation !== after.generation || before.fingerprint !== after.fingerprint) {
      return {state:'WAITING',reason:after.reason || 'arrival_during_idle_check'};
    }
    await deps.verifyCandidate();
    await deps.retireBrowser(after.owner);
    await deps.assertFrozen(before.node);
    await deps.swap(); swapped = true;
    await deps.assertFrozen(before.node);
    await deps.terminate(before.node); paused = false;
    return {state:'SWITCHED'};
  } catch (error) {
    if (swapped && paused) await deps.rollback();
    throw error;
  } finally {
    if (paused) await deps.resume(before.node);
  }
}

// Read only: unlike DriveDispatchTransport.list(), this never moves root files.
export async function readDriveIdle(config, accessToken, fetchImpl = fetch, deadline = Infinity) {
  const get = async url => {
    const remaining = Math.min(10000, deadline - Date.now());
    if (remaining <= 0) throw Error('idle inspection deadline reached');
    const response = await fetchImpl(url, {headers:{authorization:'Bearer ' + await accessToken()},signal:AbortSignal.timeout(remaining)});
    if (!response.ok) throw Error('idle Drive read HTTP ' + response.status);
    return response.json();
  };
  const list = async (q, fields) => {
    const rows = []; let pageToken = '';
    do {
      const body = await get('https://www.googleapis.com/drive/v3/files?' + new URLSearchParams({
        q,spaces:'drive',fields:'nextPageToken,files(' + fields + ')',pageSize:'1000',...(pageToken ? {pageToken} : {}),
      }));
      if (!Array.isArray(body.files)) throw Error('invalid idle Drive list');
      rows.push(...body.files); pageToken = body.nextPageToken || '';
    } while (pageToken);
    return rows;
  };
  const root = config.driveRunsFolderId;
  if (!/^[A-Za-z0-9_-]+$/.test(root)) throw Error('invalid Runs folder');
  const folders = await list("'" + root + "' in parents and trashed=false and mimeType='application/vnd.google-apps.folder'",'id,name');
  const parents = [root,...folders.filter(row => /^SR-/.test(row.name) && /^[A-Za-z0-9_-]+$/.test(row.id)).map(row => row.id)];
  const files = await list("trashed=false and mimeType='application/json' and (name contains '__SELFRUN_CONTROL__' or name contains '__SELFRUN_DISPATCH__') and ("
    + parents.map(id => "'" + id + "' in parents").join(' or ') + ')','id,name,modifiedTime,version');
  const controls = files.filter(file => /^__SELFRUN_CONTROL__.+\.json$/.test(file.name));
  let busy = false; const currentControls = new Map();
  // Bound concurrency without choosing a task's progress or meaning.
  for (let offset = 0; offset < controls.length; offset += 4) {
    await Promise.all(controls.slice(offset,offset + 4).map(async file => {
      const body = await get('https://www.googleapis.com/drive/v3/files/' + encodeURIComponent(file.id) + '?alt=media');
      if (body?.schema !== 'selfrun-task-control-v1' || file.name !== '__SELFRUN_CONTROL__' + body.task_id + '.json') throw Error('invalid idle control');
      if(currentControls.has(body.task_id))throw Error('duplicate idle control');
      currentControls.set(body.task_id,body);
      if (!terminalControl(body)) busy = true;
    }));
  }
  const dispatches=files.filter(file => /^__SELFRUN_DISPATCH__.+\.json$/.test(file.name));
  const turn=value=>/^(.*):turn:([1-9]\d*)$/.exec(String(value||''));
  for(let offset=0;offset<dispatches.length;offset+=4) {
    await Promise.all(dispatches.slice(offset,offset+4).map(async file=>{
      const body=await get('https://www.googleapis.com/drive/v3/files/'+encodeURIComponent(file.id)+'?alt=media');
      if(body?.schema!=='selfrun-server-dispatch-v1')throw Error('invalid idle dispatch');
      if(!['CREATE_REQUESTED','SEND_REQUESTED'].includes(body.client_status))return;
      const control=currentControls.get(body.task_id),a=turn(body.turn_id),b=turn(control?.turn_id);
      const exact=control&&control.turn_id===body.turn_id&&control.request_id===body.request_id;
      // A terminal app control ends older turns of its own task. It cannot
      // authorize a successor that arrived before its new control was written.
      const predecessor=a&&b&&a[1]===body.task_id&&b[1]===body.task_id&&BigInt(a[2])<BigInt(b[2]);
      if(!terminalControl(control)||!exact&&!predecessor)busy=true;
    }));
  }
  return {idle:!busy,fingerprint:hash(JSON.stringify(files.map(file => [file.id,file.name,file.modifiedTime,file.version]).sort((a,b) => a[0].localeCompare(b[0]))))};
}

export function processIdentity(pid) {
  try {
    const stat = fs.readFileSync('/proc/' + pid + '/stat','utf8');
    const fields = stat.slice(stat.lastIndexOf(')') + 2).split(' ');
    return {pid:Number(pid),state:fields[0],startTime:fields[19],
      args:fs.readFileSync('/proc/' + pid + '/cmdline','utf8').split('\0').filter(Boolean),
      cwd:fs.realpathSync('/proc/' + pid + '/cwd'),bootId:fs.readFileSync('/proc/sys/kernel/random/boot_id','utf8').trim()};
  } catch {return null;}
}
const sameProcess = (a,b) => !!a && !!b && a.pid === b.pid && a.startTime === b.startTime && a.bootId === b.bootId
  && a.cwd === b.cwd && JSON.stringify(a.args) === JSON.stringify(b.args) && !['Z','X'].includes(b.state);

export function resumeExact(proof) {
  if (sameProcess(proof, processIdentity(proof.pid))) {
    try {process.kill(proof.pid,'SIGCONT');} catch(e) {if(e.code!=='ESRCH')throw e;}
  }
}

async function jsonFile(file) {return JSON.parse(await fsp.readFile(file,'utf8'));}
async function atomicJson(file, body) {
  const tmp = file + '.' + process.pid + '.tmp';
  await fsp.writeFile(tmp,JSON.stringify(body,null,2) + '\n',{mode:0o600}); await fsp.rename(tmp,file);
}

function checkedManifest(input) {
  const names = ['currentServerRoot','candidateServerRoot','launcherLink','expectedLauncher','candidateLauncher','dataDir'];
  if (input.schema !== 'selfrun-idle-patch-v1' || names.some(key => !path.isAbsolute(input[key] || ''))
    || !/^[a-f0-9]{40}$/.test(input.sourceCommit || '') || !/^[a-f0-9]{64}$/.test(input.launcherSha256 || '')
    || typeof input.version !== 'string' || !input.version || typeof input.currentVersion !== 'string'
    || !Number.isSafeInteger(input.serverPort) || !Number.isSafeInteger(input.browserPort)) throw Error('invalid idle patch manifest');
  return input;
}

export async function realDependencies(manifest) {
  const m = checkedManifest(manifest);
  const {GoogleOAuthTokenProvider} = await import(pathToFileURL(path.join(m.currentServerRoot,'src/google-oauth.mjs')).href);
  const {extensionProcessMatches,extensionProcessExited} = await import(pathToFileURL(path.join(m.currentServerRoot,'src/browser/extension-manager.mjs')).href);
  const provider = new GoogleOAuthTokenProvider({driveAuthTokenFile:path.join(m.dataDir,'drive-auth-token.json'),driveAuthServiceSocket:m.driveAuthServiceSocket});
  let frozen = null, watchdog = null, deadline = Infinity, swapped = false;
  const journalPath = path.join(m.dataDir,'extension-ownership.json');
  const assertLink = async expected => {
    if (!(await fsp.lstat(m.launcherLink)).isSymbolicLink() || await fsp.realpath(m.launcherLink) !== expected) throw Error('launcher changed');
  };
  const health = async () => {
    const response = await fetch('http://127.0.0.1:' + m.serverPort + '/health',{signal:AbortSignal.timeout(3000)});
    if (!response.ok) throw Error('health read failed'); return response.json();
  };
  const node = () => {
    const rows = fs.readdirSync('/proc').filter(name => /^\d+$/.test(name)).map(processIdentity)
      .filter(row => row?.cwd === m.currentServerRoot && /(^|\/)node$/.test(row.args[0]) && row.args[1] === 'src/server.mjs');
    if (rows.length !== 1) throw Error('exact current server process not found'); return rows[0];
  };
  const browserDeps = {processIdentity,processExists:pid => fs.existsSync('/proc/' + pid),bootId:() => fs.readFileSync('/proc/sys/kernel/random/boot_id','utf8').trim()};
  const ownerCheck = async journal => {
    if (journal.schema !== 'selfrun-extension-ownership-v1' || !Array.isArray(journal.targets) || journal.targets.length
      || !emptyObject(journal.targetMetadata) || journal.controllerStopPending || journal.retiringProcess) return false;
    if (!journal.process) return true;
    if (extensionProcessExited(journal.process,browserDeps)) return true;
    const proof = journal.process;
    if (!extensionProcessMatches(proof,processIdentity(proof.pid),browserDeps.bootId())) throw Error('browser process proof unavailable');
    const endpoint = 'http://127.0.0.1:' + m.browserPort;
    const version = await fetch(endpoint + '/json/version',{signal:AbortSignal.timeout(2000)}).then(r => r.json());
    if (proof.browserKey && version.webSocketDebuggerUrl !== proof.browserKey) throw Error('browser endpoint changed');
    const targets = await fetch(endpoint + '/json/list',{signal:AbortSignal.timeout(2000)}).then(r => r.json());
    if (!Array.isArray(targets)) throw Error('browser inventory unavailable');
    if(!proof.browserKey){
      // A probe-only browser has not bound an action endpoint yet. Prove the
      // exact paired monitor on this endpoint before treating it as empty.
      const pair=await jsonFile(path.join(m.dataDir,'extension-pairing.json'));
      if(pair.ownerId!==journal.ownerId||typeof pair.capability!=='string')return false;
      const key=hash('selfrun-page-bootstrap:'+pair.capability+':'+journal.browserSessionId),challenge=randomUUID();
      const expected=createHmac('sha256',key).update(challenge).digest('hex');
      const row=targets.find(target=>target.type==='page'&&target.url===`chrome-extension://${EXTENSION_ID}/controller.html`);
      if(!row||await attestController({row,port:m.browserPort,challenge,signal:AbortSignal.timeout(2000)})!==expected)return false;
    }
    return idleBrowserTargets(targets);
  };
  const assertFrozen = async proof => {
    const current = processIdentity(proof.pid);
    if (Date.now() >= deadline || !sameProcess(proof,current) || !['T','t'].includes(current.state)) throw Error('server freeze no longer held');
  };
  const swapLink = async target => {
    const tmp = m.launcherLink + '.patch-' + randomUUID();
    await fsp.symlink(target,tmp); try {await fsp.rename(tmp,m.launcherLink);} finally {await fsp.unlink(tmp).catch(() => {});}
  };
  const deps = {
    health,
    async applied() {
      const h=await health();
      if(h.version!==m.version || await fsp.realpath(m.launcherLink)!==m.candidateLauncher)return false;
      const rows=fs.readdirSync('/proc').filter(name=>/^\d+$/.test(name)).map(processIdentity)
        .filter(row=>row?.cwd===m.candidateServerRoot&&/(^|\/)node$/.test(row.args[0])&&row.args[1]==='src/server.mjs'&&!['Z','X'].includes(row.state));
      if(rows.length!==1)return false;
      await deps.verifyCandidate();return true;
    },
    async inspect(isFrozen,proof) {
      await assertLink(m.expectedLauncher);
      const state = await jsonFile(path.join(m.dataDir,'state.json'));
      if (!idleSnapshot(state)) return {idle:false,reason:'current_tasks_or_resources'};
      const currentNode = isFrozen ? proof : node();
      if (isFrozen) await assertFrozen(proof);
      else {
        const h = await health(), w = h.drive_watcher;
        if (h.version !== m.currentVersion || h.generation !== state.generation) return {idle:false,reason:'server_changed'};
        // Queued discovery reads are not active tasks. The direct Drive read below
        // is repeated with the exact process frozen before any launcher swap.
        if (!w || h.browser_runtime?.pending) return {idle:false,reason:'intake_in_flight'};
      }
      const raw = await fsp.readFile(journalPath,'utf8'), journal = JSON.parse(raw);
      if (!await ownerCheck(journal)) return {idle:false,reason:'browser_resources'};
      const drive = await readDriveIdle(m,() => provider.accessToken(),fetch,deadline);
      if (!drive.idle) return {idle:false,reason:'app_task_control'};
      return {idle:true,generation:state.generation,node:currentNode,owner:journal,
        fingerprint:hash(JSON.stringify([state.generation,currentNode.pid,currentNode.startTime,drive.fingerprint,hash(raw)]))};
    },
    async pause(proof) {
      if (!sameProcess(proof,processIdentity(proof.pid))) throw Error('server identity changed');
      // Independent one-shot failsafe also resumes after an updater crash/SIGKILL.
      const watchdogCode = `const fs=require('node:fs');const {proof:p,link,old,next}=JSON.parse(process.argv[1]);setTimeout(()=>{try{const s=fs.readFileSync('/proc/'+p.pid+'/stat','utf8');const f=s.slice(s.lastIndexOf(')')+2).split(' ');if(f[19]!==p.startTime||fs.readFileSync('/proc/sys/kernel/random/boot_id','utf8').trim()!==p.bootId)return;try{if(fs.realpathSync(link)===next){const tmp=link+'.resume-'+process.pid;fs.symlinkSync(old,tmp);fs.renameSync(tmp,link);}}finally{process.kill(p.pid,'SIGCONT');}}catch{}},45000);`;
      watchdog = spawn(process.execPath,['-e',watchdogCode,JSON.stringify({proof,link:m.launcherLink,old:m.expectedLauncher,next:m.candidateLauncher})],{stdio:'ignore',detached:true}); watchdog.unref();
      deadline = Date.now() + 40000; frozen = proof;
      process.kill(proof.pid,'SIGSTOP');
      for (let n = 0; n < 20; n++) {if (['T','t'].includes(processIdentity(proof.pid)?.state)) return; await sleep(10);}
      resumeExact(proof); frozen = null; throw Error('server did not pause');
    },
    async resume(proof) {resumeExact(proof);frozen = null;deadline = Infinity;if(watchdog){watchdog.kill();watchdog=null;}},
    async verifyCandidate() {
      const root = path.dirname(m.candidateServerRoot);
      const sha = (await exec('git',['-C',root,'rev-parse','HEAD'])).stdout.trim();
      const changes = (await exec('git',['-C',root,'status','--porcelain','--untracked-files=normal'])).stdout;
      if (sha !== m.sourceCommit || changes) throw Error('candidate source changed');
      if (hash(await fsp.readFile(m.candidateLauncher)) !== m.launcherSha256) throw Error('candidate launcher changed');
      const pkg = await jsonFile(path.join(m.candidateServerRoot,'package.json'));
      if (pkg.version !== m.version) throw Error('candidate version changed');
    },
    async retireBrowser(journal) {
      if (!await ownerCheck(journal)) throw Error('browser no longer empty');
      if (!journal.process || extensionProcessExited(journal.process,browserDeps)) return;
      if (!extensionProcessMatches(journal.process,processIdentity(journal.process.pid),browserDeps.bootId())) throw Error('browser identity changed');
      process.kill(journal.process.pid,'SIGTERM');
      for (let n = 0; n < 100; n++) {if (extensionProcessExited(journal.process,browserDeps)) return;await sleep(50);}
      throw Error('empty dedicated browser exit unconfirmed');
    },
    assertFrozen,
    swap:async()=>{await assertLink(m.expectedLauncher);await swapLink(m.candidateLauncher);swapped=true;},
    rollback:async()=>{if(await fsp.realpath(m.launcherLink)===m.candidateLauncher)await swapLink(m.expectedLauncher);swapped=false;},
    async terminate(proof) {
      const current = processIdentity(proof.pid);
      if (Date.now() >= deadline || !sameProcess(proof,current) || !['T','t'].includes(current.state)) throw Error('server freeze no longer held');
      if (await fsp.realpath(m.launcherLink) !== m.candidateLauncher) throw Error('candidate launcher no longer selected');
      process.kill(proof.pid,'SIGKILL'); // Empty frozen runtime cannot execute queued input.
      frozen = null;if(watchdog){watchdog.kill();watchdog=null;}
    },
    release:()=>{
      if(frozen && sameProcess(frozen,processIdentity(frozen.pid))) {
        try {
          if(swapped && fs.realpathSync(m.launcherLink)===m.candidateLauncher) {
            const tmp=m.launcherLink+'.restore-'+process.pid;fs.symlinkSync(m.expectedLauncher,tmp);fs.renameSync(tmp,m.launcherLink);swapped=false;
          }
        } finally {resumeExact(frozen);}
      }
      if(watchdog)watchdog.kill();
    },
  };
  return deps;
}

export async function main(file) {
  const manifest = checkedManifest(await jsonFile(file));
  const statusFile = file + '.status.json', lockFile = path.join(manifest.dataDir,'idle-patch.lock');
  let lock;
  try {lock = await fsp.open(lockFile,'wx',0o600);} catch (e) {
    if(e.code!=='EEXIST')throw e;
    const old=await jsonFile(lockFile),row=processIdentity(old.proof?.pid);
    if(!old.proof || !row && fs.existsSync('/proc/'+old.proof.pid) || sameProcess(old.proof,row)
      || row && old.proof.startTime===row.startTime && old.proof.bootId===row.bootId) throw Error('idle patch already registered');
    // Only an exited exact previous updater permits replacing its own stale lock.
    await fsp.unlink(lockFile);lock=await fsp.open(lockFile,'wx',0o600);
  }
  await lock.writeFile(JSON.stringify({proof:processIdentity(process.pid),manifest:file,startedAt:new Date().toISOString()})); await lock.close();
  const deps = await realDependencies(manifest);
  const restore = () => deps.release();
  const stop = () => {restore();process.exit(0);};
  process.on('exit',restore);process.on('SIGTERM',stop);process.on('SIGINT',stop);
  const status = (state,reason) => atomicJson(statusFile,{schema:'selfrun-idle-patch-status-v1',state,reason,pid:process.pid,
    version:manifest.version,sourceCommit:manifest.sourceCommit,updatedAt:new Date().toISOString()});
  try {
    await deps.verifyCandidate();
    for (;;) {
      let result;
      try {result=await attemptIdlePatch(deps);} catch (e) {await status('WAITING',String(e.message).slice(0,200));await sleep(15000);continue;}
      if(result.state==='SWITCHED') {
        await status('STARTING','launcher_switched');
        for(let n=0;n<60;n++) {
          try {if(await deps.applied()){await status('APPLIED','new_version_source_process_health_verified');return;}}catch{}
          await sleep(1000);
        }
        await status('APPLIED_UNVERIFIED','new_runtime_health_not_observed');return;
      }
      await status('WAITING',result.reason);await sleep(15000);
    }
  } finally {restore();await fsp.unlink(lockFile).catch(()=>{});process.off('exit',restore);process.off('SIGTERM',stop);process.off('SIGINT',stop);}
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  main(process.argv[2]).catch(error => {console.error('IDLE_PATCH_ERROR ' + String(error.message));process.exitCode=1;});
}
