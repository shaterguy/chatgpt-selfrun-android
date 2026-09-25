const CONTROL_PATHS = ['model', 'thinking_effort', 'conversation_origin', 'service_tier'];

const delay = (ms, signal) => new Promise((resolve, reject) => {
  if (signal?.aborted) return reject(signal.reason || new Error('aborted'));
  const timer = setTimeout(resolve, ms);
  signal?.addEventListener('abort', () => {
    clearTimeout(timer);
    reject(signal.reason || new Error('aborted'));
  }, { once: true });
});

async function evaluate(session, expression) {
  const result = await session.call('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.text || 'Browser script evaluation failed');
  }
  return result.result?.value;
}

function normalizeOperations(raw) {
  if (!Array.isArray(raw) || raw.length !== CONTROL_PATHS.length) {
    throw new Error('profile_operations must contain four controls');
  }
  const byPath = new Map();
  for (const operation of raw) {
    const op = String(operation?.op || '').toUpperCase();
    const path = String(operation?.path || '');
    if (!CONTROL_PATHS.includes(path) || (op !== 'SET' && op !== 'REMOVE') || byPath.has(path)) {
      throw new Error('invalid profile operation');
    }
    if (op === 'SET' && (typeof operation.value !== 'string' || operation.value.length > 512)) {
      throw new Error('invalid profile operation value');
    }
    byPath.set(path, op === 'SET' ? { op, path, value: operation.value } : { op, path });
  }
  return CONTROL_PATHS.map((path) => {
    const operation = byPath.get(path);
    if (!operation) throw new Error('incomplete profile operations');
    return operation;
  });
}

function profilePatchScript(rawOperations, markerKey) {
  const operations = JSON.stringify(normalizeOperations(rawOperations));
  const marker = JSON.stringify(markerKey);
  const controls = JSON.stringify(CONTROL_PATHS);
  return "(() => {" +
    "const CONTROL=" + controls + ";" +
    "const operations=" + operations + ";" +
    "const markerKey=" + marker + ";" +
    "const sameOrigin=url=>{try{return new URL(url,location.href).origin===location.origin;}catch(_){return false;}};" +
    "const route=url=>{try{let p=new URL(url,location.href).pathname.toLowerCase();if(p.length>1)p=p.replace(/\\\\/+$/,'');return p==='/backend-api/conversation'||p==='/backend-api/f/conversation';}catch(_){return false;}};" +
    "const patch=body=>{" +
      "if(!body||typeof body!=='object'||Array.isArray(body)||!Array.isArray(body.messages))throw new Error('unknown conversation schema');" +
      "const before={...body};for(const key of CONTROL)delete before[key];const out={...body};" +
      "for(const operation of operations){if(operation.op==='SET')out[operation.path]=operation.value;else delete out[operation.path];}" +
      "const after={...out};for(const key of CONTROL)delete after[key];" +
      "if(JSON.stringify(before)!==JSON.stringify(after))throw new Error('conversation data plane changed');" +
      "try{localStorage.setItem(markerKey,'1');}catch(_){}return out;};" +
    "const nativeFetch=window.fetch.bind(window);" +
    "window.fetch=async function(input,init){" +
      "let request;try{const source=typeof Request!=='undefined'&&input instanceof Request?input.clone():input;request=new Request(source,init);}catch(_){return nativeFetch(input,init);}" +
      "if(String(request.method||'GET').toUpperCase()!=='POST'||!sameOrigin(request.url)||!route(request.url))return nativeFetch(input,init);" +
      "let body;try{body=JSON.parse(await request.clone().text());}catch(error){return Promise.reject(error);}" +
      "try{return nativeFetch(new Request(request,{body:JSON.stringify(patch(body))}));}catch(error){return Promise.reject(error);}};" +
    "const nativeOpen=XMLHttpRequest.prototype.open,nativeSend=XMLHttpRequest.prototype.send,meta=new WeakMap();" +
    "XMLHttpRequest.prototype.open=function(method,url,...rest){meta.set(this,{method:String(method||''),url:String(url||'')});return nativeOpen.call(this,method,url,...rest);};" +
    "XMLHttpRequest.prototype.send=function(body){const value=meta.get(this)||{method:'',url:''};" +
      "if(value.method.toUpperCase()!=='POST'||!sameOrigin(value.url)||!route(value.url))return nativeSend.call(this,body);" +
      "return nativeSend.call(this,JSON.stringify(patch(JSON.parse(String(body)))));};" +
    "})();";
}

function probeExpression(markerKey) {
  const marker = JSON.stringify(markerKey || '');
  return "(() => {" +
    "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;" +
    "const buttons=[...document.querySelectorAll('button')].filter(visible);" +
    "const selectors=['textarea#prompt-textarea','textarea[data-testid=\"prompt-textarea\"]','div#prompt-textarea[contenteditable=\"true\"]','main form [contenteditable=\"true\"][data-lexical-editor=\"true\"]','main form [contenteditable=\"true\"]'];" +
    "let composer=null;for(const selector of selectors){composer=[...document.querySelectorAll(selector)].find(visible);if(composer)break;}" +
    "const stop=buttons.some(b=>b.dataset.testid==='stop-button'||/stop|중지/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')));" +
    "const assistants=[...document.querySelectorAll('[data-message-author-role=\"assistant\"]')];" +
    "const users=[...document.querySelectorAll('[data-message-author-role=\"user\"]')];" +
    "if(!window.__selfRunServerMutationState){const state={count:0};const observer=new MutationObserver(()=>{state.count+=1;});const activityRoot=document.querySelector('main')||document.body;if(activityRoot)observer.observe(activityRoot,{subtree:true,childList:true,characterData:true});window.__selfRunServerMutationState=state;}" +
    "const mutationCount=Number(window.__selfRunServerMutationState?.count||0);" +
    "const last=assistants.length?assistants[assistants.length-1]:null;" +
    "const body=String(document.body?.innerText||'');const errorMatch=body.match(/something went wrong|error generating|문제가 발생|오류가 발생/i);" +
    "let postObserved=false;try{postObserved=!!" + marker + "&&localStorage.getItem(" + marker + ")==='1';}catch(_){}" +
    "return {url:location.href,title:document.title,readyState:document.readyState,loginPage:/\\/auth(?:\\/|$)|\\/login(?:\\/|$)/i.test(location.pathname)," +
      "composer:!!composer,streaming:stop,assistantCount:assistants.length,userCount:users.length,mutationCount," +
      "assistantTextLength:String(last?.innerText||last?.textContent||'').length,postObserved,errorText:errorMatch?errorMatch[0]:null};" +
    "})()";
}

function newChatExpression() {
  return "(() => {" +
    "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;const parts=location.pathname.split('/').filter(Boolean);" +
    "const inConversation=parts.includes('c')&&parts.indexOf('c')+1<parts.length;if(!inConversation)return {status:'READY',url:location.href};" +
    "const label=e=>String(e.innerText||e.textContent||e.getAttribute?.('aria-label')||'').replace(/\\s+/g,' ').trim();" +
    "const control=[...document.querySelectorAll('button,a,[role=\"button\"]')].filter(visible).find(e=>/^(new chat|new conversation|새 채팅|새 대화)$/i.test(label(e)));" +
    "if(!control)return {status:'MISSING',url:location.href};control.focus?.();control.click();return {status:'CLICKED',url:location.href};})()";
}

function inputExpression(prompt) {
  const expected = JSON.stringify(prompt);
  return "(() => {" +
    "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;" +
    "const selectors=['textarea#prompt-textarea','textarea[data-testid=\"prompt-textarea\"]','div#prompt-textarea[contenteditable=\"true\"]','main form [contenteditable=\"true\"][data-lexical-editor=\"true\"]','main form [contenteditable=\"true\"]'];" +
    "let composer=null;for(const selector of selectors){composer=[...document.querySelectorAll(selector)].find(visible);if(composer)break;}if(!composer)return {status:'NO_COMPOSER'};" +
    "const expected=" + expected + ";composer.focus();" +
    "if('value' in composer){const proto=Object.getPrototypeOf(composer);const own=Object.getOwnPropertyDescriptor(proto,'value');" +
      "const base=typeof HTMLTextAreaElement!=='undefined'?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null;" +
      "const setter=own?.set||base?.set;if(setter)setter.call(composer,expected);else composer.value=expected;" +
      "composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));composer.dispatchEvent(new Event('change',{bubbles:true}));}" +
    "else{composer.replaceChildren(document.createTextNode(expected));composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));}" +
    "const raw=('value' in composer)?composer.value:(composer.innerText||composer.textContent||'');const scope=composer.closest('form')||document;" +
    "const send=[...scope.querySelectorAll('button')].filter(visible).find(b=>b.dataset.testid==='send-button'||b.dataset.testid==='composer-submit-button'||/send|보내기|submit/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')));" +
    "return {status:raw===expected&&send&&!send.disabled&&send.getAttribute('aria-disabled')!=='true'?'READY':'WAIT'};})()";
}

function submitExpression() {
  return "(() => {" +
    "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;" +
    "const composer=[...document.querySelectorAll('textarea#prompt-textarea,textarea[data-testid=\"prompt-textarea\"],div#prompt-textarea[contenteditable=\"true\"],main form [contenteditable=\"true\"]')].find(visible);" +
    "if(!composer)return {status:'NO_COMPOSER'};const scope=composer.closest('form')||document;" +
    "const send=[...scope.querySelectorAll('button')].filter(visible).find(b=>b.dataset.testid==='send-button'||b.dataset.testid==='composer-submit-button'||/send|보내기|submit/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')));" +
    "if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return {status:'NO_SEND'};send.focus?.();send.click();return {status:'SUBMITTED'};})()";
}

function canonicalConversationUrl(raw) {
  const url = new URL(raw);
  const parts = url.pathname.split('/').filter(Boolean);
  const index = parts.indexOf('c');
  const id = index >= 0 && index + 1 < parts.length ? parts[index + 1] : '';
  if (!/^[A-Za-z0-9-]+$/.test(id)) throw new Error('canonical conversation URL missing');
  return 'https://chatgpt.com/c/' + id;
}

export class ChatGptBrowser {
  constructor(chromium, config) {
    this.chromium = chromium;
    this.config = config;
  }

  async #waitFor(session, predicate, options) {
    const started = Date.now();
    let last = null;
    while (Date.now() - started < options.timeoutMs) {
      if (options.signal?.aborted) throw options.signal.reason || new Error('aborted');
      last = await evaluate(session, probeExpression(options.markerKey || ''));
      if (predicate(last)) return last;
      await delay(250, options.signal);
    }
    const error = new Error('Timed out waiting for ' + options.label);
    error.lastProbe = last;
    throw error;
  }

  async #prepareNewChat(session, projectUrl, signal, markerKey) {
    await session.call('Page.navigate', { url: projectUrl });
    await this.#waitFor(session, (p) => p.readyState === 'complete' || p.readyState === 'interactive', {
      timeoutMs: this.config.navigationTimeoutMs, signal, label: 'project page', markerKey,
    });
    let probe = await evaluate(session, probeExpression(markerKey));
    if (probe.loginPage) {
      const error = new Error('ChatGPT browser profile requires sign-in');
      error.code = 'AUTH_REQUIRED';
      throw error;
    }
    for (let attempt = 0; attempt < 3; attempt += 1) {
      if (!/\/c\//.test(new URL(probe.url).pathname)) break;
      const result = await evaluate(session, newChatExpression());
      if (result?.status === 'MISSING') break;
      await delay(700, signal);
      probe = await evaluate(session, probeExpression(markerKey));
    }
    return this.#waitFor(session, (p) => p.composer, {
      timeoutMs: this.config.navigationTimeoutMs, signal, label: 'ChatGPT composer', markerKey,
    });
  }

  async prepare(options) {
    const target = await this.chromium.createTarget('about:blank');
    const session = await this.chromium.connectTarget(target);
    const markerKey = 'selfrun-server:post:' + options.markerId;
    await session.call('Page.enable');
    await session.call('Runtime.enable');
    await session.call('Page.addScriptToEvaluateOnNewDocument', {
      source: profilePatchScript(options.profileOperations, markerKey),
    });
    try {
      const before = await this.#prepareNewChat(session, options.projectUrl, options.signal, markerKey);
      const baseline = {
        assistantCount: before.assistantCount || 0,
        userCount: before.userCount || 0,
        assistantTextLength: before.assistantTextLength || 0,
      };
      let staged = null;
      const started = Date.now();
      while (Date.now() - started < this.config.navigationTimeoutMs) {
        if (options.signal?.aborted) throw options.signal.reason || new Error('aborted');
        staged = await evaluate(session, inputExpression(options.prompt));
        if (staged?.status === 'READY') break;
        await delay(250, options.signal);
      }
      if (staged?.status !== 'READY') throw new Error('Composer did not become ready to submit');
      return { target, session, baseline, markerKey };
    } catch (error) {
      session.close();
      await this.chromium.closeTarget(target.id);
      throw error;
    }
  }

  async submitPrepared(prepared, options) {
    if (options.signal?.aborted) throw options.signal.reason || new Error('aborted');
    const sent = await evaluate(prepared.session, submitExpression());
    if (sent?.status !== 'SUBMITTED') throw new Error('Composer send failed: ' + (sent?.status || 'unknown'));
    await options.onSubmitted?.();
    const started = await this.#waitFor(
      prepared.session,
      (p) => p.postObserved && /\/c\//.test(new URL(p.url).pathname),
      {
        timeoutMs: this.config.startConfirmationTimeoutMs,
        signal: options.signal,
        label: 'canonical conversation start',
        markerKey: prepared.markerKey,
      }
    );
    return { url: canonicalConversationUrl(started.url), probe: started };
  }

  async sendContinuation(session, prompt, signal) {
    const staged = await evaluate(session, inputExpression(prompt));
    if (staged?.status !== 'READY') throw new Error('recovery composer unavailable');
    if (signal?.aborted) throw signal.reason || new Error('aborted');
    const sent = await evaluate(session, submitExpression());
    if (sent?.status !== 'SUBMITTED') throw new Error('recovery send unavailable');
  }

  async attachConversation(options) {
    const target = await this.chromium.createTarget('about:blank');
    const session = await this.chromium.connectTarget(target);
    const markerKey = 'selfrun-server:post:' + options.markerId;
    await session.call('Page.enable');
    await session.call('Runtime.enable');
    await session.call('Page.addScriptToEvaluateOnNewDocument', {
      source: profilePatchScript(options.profileOperations, markerKey),
    });
    try {
      await session.call('Page.navigate', { url: options.conversationUrl });
      const probe = await this.#waitFor(session, (p) => p.composer && /\/c\//.test(new URL(p.url).pathname), {
        timeoutMs: this.config.navigationTimeoutMs,
        signal: options.signal,
        label: 'existing conversation',
        markerKey,
      });
      return {
        target,
        session,
        markerKey,
        baseline: {
          assistantCount: probe.assistantCount || 0,
          userCount: probe.userCount || 0,
          assistantTextLength: probe.assistantTextLength || 0,
        },
      };
    } catch (error) {
      session.close();
      await this.chromium.closeTarget(target.id);
      throw error;
    }
  }

  async monitor(options) {
    const prepared = options.prepared;
    let previous = await evaluate(prepared.session, probeExpression(prepared.markerKey));
    let lastActivityAt = Date.now();
    let recoveryCount = 0;
    let lastReported = '';

    while (!options.signal?.aborted) {
      const probe = await evaluate(prepared.session, probeExpression(prepared.markerKey));
      const changed =
        probe.url !== previous.url ||
        probe.streaming !== previous.streaming ||
        probe.assistantCount !== previous.assistantCount ||
        probe.userCount !== previous.userCount ||
        probe.mutationCount !== previous.mutationCount ||
        probe.assistantTextLength !== previous.assistantTextLength;
      if (changed) lastActivityAt = Date.now();

      if (probe.errorText) {
        await options.onActivity?.({ status: 'PAGE_ERROR', probe, lastActivityAt, recoveryCount });
        return { status: 'PAGE_ERROR', probe };
      }

      const idleMs = Date.now() - lastActivityAt;
      let status = probe.streaming ? 'RUNNING' : 'IDLE';
      if (idleMs >= this.config.livenessRecoveryMs) {
        await this.sendContinuation(prepared.session, this.config.recoveryPrompt, options.signal);
        recoveryCount += 1;
        lastActivityAt = Date.now();
        status = 'RECOVERY_SENT';
        await options.onRecovery?.({ recoveryCount, probe, lastActivityAt });
      }
      if (changed || status !== lastReported) {
        await options.onActivity?.({ status, probe, lastActivityAt, recoveryCount });
        lastReported = status;
      }
      previous = probe;
      await delay(this.config.probeIntervalMs, options.signal);
    }
    return { status: 'ABORTED' };
  }
}
