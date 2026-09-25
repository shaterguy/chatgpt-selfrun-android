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
    const detail = result.exceptionDetails.exception?.description
      || result.exceptionDetails.exception?.value
      || result.exceptionDetails.text
      || 'Browser script evaluation failed';
    throw new Error(String(detail));
  }
  return result.result?.value;
}

function conversationId(url) {
  try {
    const parts = new URL(url).pathname.split('/').filter(Boolean);
    const index = parts.indexOf('c');
    const raw = index >= 0 && index + 1 < parts.length ? parts[index + 1] : '';
    const id = decodeURIComponent(raw);
    if (!id || id.toLowerCase().startsWith('local-chatgpt')) return '';
    return /^[A-Za-z0-9_-]{1,160}$/.test(id) ? id : '';
  } catch {
    return '';
  }
}

function probeExpression() {
  return `(() => {
    const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;
    const buttons=[...document.querySelectorAll('button')].filter(visible);
    const selectors=['textarea#prompt-textarea','textarea[data-testid="prompt-textarea"]',
      'div#prompt-textarea[contenteditable="true"]','main form [contenteditable="true"][data-lexical-editor="true"]',
      'main form [contenteditable="true"]'];
    let composer=null;
    for(const selector of selectors){composer=[...document.querySelectorAll(selector)].find(visible);if(composer)break;}
    const stop=buttons.some(b=>b.dataset.testid==='stop-button'||/stop|중지/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')));
    const paused=buttons.some(b=>/resume|continue generating|재개|계속 생성/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')+' '+(b.innerText||'')));
    const assistants=[...document.querySelectorAll('[data-message-author-role="assistant"],[data-chatgpt-search-unit-key$=":assistant"],[data-content-search-unit-key$=":assistant"]')];
    const last=assistants.length?assistants[assistants.length-1]:null;
    const users=[...document.querySelectorAll('[data-message-author-role="user"],[data-chatgpt-search-unit-key$=":user"],[data-content-search-unit-key$=":user"]')];
    const lastUser=users.length?users[users.length-1]:null;
    const messageId=e=>{
      for(let node=e,depth=0;node&&depth<6;node=node.parentElement,depth+=1){
        for(const name of ['data-message-id','data-chatgpt-search-unit-key','data-content-search-unit-key']){
          const value=String(node.getAttribute?.(name)||'').trim();
          if(value)return name+':'+value;
        }
        const testid=String(node.getAttribute?.('data-testid')||'').trim();
        if(/conversation-turn|message/i.test(testid))return 'data-testid:'+testid;
        const id=String(node.id||'').trim();
        if(/conversation|message|turn/i.test(id))return 'id:'+id;
      }
      return null;
    };
    const body=String(document.body?.innerText||'');
    const errorMatch=body.match(/something went wrong|error generating|문제가 발생|오류가 발생/i);
    return {
      url:location.href,
      title:document.title,
      readyState:document.readyState,
      loginPage:(()=>{const p=String(location.pathname||'').toLowerCase();return p==='/auth'||p.startsWith('/auth/')||p==='/login'||p.startsWith('/login/');})(),
      composer:!!composer,
      streaming:stop,
      paused,
      assistantCount:assistants.length,
      assistantTextLength:String(last?.innerText||last?.textContent||'').length,
      assistantMessageId:messageId(last),
      userCount:users.length,
      userTextLength:String(lastUser?.innerText||lastUser?.textContent||'').length,
      userMessageId:messageId(lastUser),
      bodyTextLength:body.length,
      errorText:errorMatch?errorMatch[0]:null
    };
  })()`;
}

function newChatExpression() {
  return `(() => {
    const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;
    const parts=location.pathname.split('/').filter(Boolean);
    const inConversation=parts.includes('c')&&parts.indexOf('c')+1<parts.length;
    if(!inConversation)return {status:'READY',url:location.href};
    const label=e=>String(e.innerText||e.textContent||e.getAttribute?.('aria-label')||'').replace(/\\s+/g,' ').trim();
    const control=[...document.querySelectorAll('button,a,[role="button"]')].filter(visible)
      .find(e=>/^(new chat|new conversation|새 채팅|새 대화)$/i.test(label(e)));
    if(!control)return {status:'MISSING',url:location.href};
    control.focus?.();control.click();
    return {status:'CLICKED',url:location.href};
  })()`;
}

function inputExpression(prompt) {
  const expected = JSON.stringify(prompt);
  return `(() => {
    const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;
    const selectors=['textarea#prompt-textarea','textarea[data-testid="prompt-textarea"]',
      'div#prompt-textarea[contenteditable="true"]','main form [contenteditable="true"][data-lexical-editor="true"]',
      'main form [contenteditable="true"]'];
    let composer=null;
    for(const selector of selectors){composer=[...document.querySelectorAll(selector)].find(visible);if(composer)break;}
    if(!composer)return {status:'NO_COMPOSER'};
    const expected=${expected};
    composer.focus();
    if('value' in composer){
      const proto=Object.getPrototypeOf(composer);
      const own=Object.getOwnPropertyDescriptor(proto,'value');
      const base=typeof HTMLTextAreaElement!=='undefined'?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null;
      const setter=own?.set||base?.set;
      if(setter)setter.call(composer,expected);else composer.value=expected;
      composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));
      composer.dispatchEvent(new Event('change',{bubbles:true}));
    }else{
      composer.replaceChildren(document.createTextNode(expected));
      composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));
    }
    const raw=('value' in composer)?composer.value:(composer.innerText||composer.textContent||'');
    return {status:raw===expected?'READY':'MISMATCH',length:String(raw).length};
  })()`;
}

function sendReadyExpression() {
  return `(() => {
    const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;
    const selectors=['textarea#prompt-textarea','textarea[data-testid="prompt-textarea"]',
      'div#prompt-textarea[contenteditable="true"]','main form [contenteditable="true"][data-lexical-editor="true"]',
      'main form [contenteditable="true"]'];
    let composer=null;
    for(const selector of selectors){composer=[...document.querySelectorAll(selector)].find(visible);if(composer)break;}
    if(!composer)return {ready:false,status:'NO_COMPOSER'};
    const scope=composer.closest('form')||document;
    const send=[...scope.querySelectorAll('button')].filter(visible)
      .find(b=>b.dataset.testid==='send-button'||b.dataset.testid==='composer-submit-button'||
        /send|보내기|submit/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')));
    return {ready:!!send&&!send.disabled&&send.getAttribute('aria-disabled')!=='true',
      status:send?'SEND_FOUND':'NO_SEND'};
  })()`;
}

function submitExpression() {
  return `(() => {
    const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;
    const selectors=['textarea#prompt-textarea','textarea[data-testid="prompt-textarea"]',
      'div#prompt-textarea[contenteditable="true"]','main form [contenteditable="true"][data-lexical-editor="true"]',
      'main form [contenteditable="true"]'];
    let composer=null;
    for(const selector of selectors){composer=[...document.querySelectorAll(selector)].find(visible);if(composer)break;}
    if(!composer)return {status:'NO_COMPOSER'};
    const scope=composer.closest('form')||document;
    const send=[...scope.querySelectorAll('button')].filter(visible)
      .find(b=>b.dataset.testid==='send-button'||b.dataset.testid==='composer-submit-button'||
        /send|보내기|submit/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')));
    if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return {status:'NO_SEND'};
    send.focus?.();send.click();
    return {status:'SUBMITTED'};
  })()`;
}

function isConversationRequest(url, method) {
  if (String(method || '').toUpperCase() !== 'POST') return false;
  try {
    const path = new URL(url).pathname.toLowerCase().replace(/\/+$/, '');
    return path === '/backend-api/conversation'
      || path === '/backend-api/f/conversation'
      || path === '/backend-api/conversation/init';
  } catch {
    return false;
  }
}

function applyProfileOperations(body, operations = []) {
  const out = { ...body };
  const allowed = new Set(['model', 'thinking_effort', 'conversation_origin', 'service_tier']);
  for (const operation of operations) {
    const path = String(operation?.path || '');
    const op = String(operation?.op || '').toUpperCase();
    if (!allowed.has(path)) throw new Error('Profile operation is not allowlisted');
    if (op === 'SET') {
      const value = String(operation.value ?? '');
      out[path] = value;
      if (path === 'model' && Object.prototype.hasOwnProperty.call(out, 'requested_default_model')) {
        out.requested_default_model = value;
      }
    } else if (op === 'REMOVE') {
      delete out[path];
      if (path === 'model' && Object.prototype.hasOwnProperty.call(out, 'requested_default_model')) {
        delete out.requested_default_model;
      }
    } else throw new Error('Unknown profile operation');
  }
  return out;
}

export class ChatGptBrowser {
  constructor(chromium, config) {
    this.chromium = chromium;
    this.config = config;
  }

  async #waitFor(session, predicate, { timeoutMs, signal, label }) {
    const started = Date.now();
    let last = null;
    while (Date.now() - started < timeoutMs) {
      if (signal?.aborted) throw signal.reason || new Error('aborted');
      try {
        last = await evaluate(session, probeExpression());
      } catch (error) {
        last = { probeError: String(error?.message || error) };
        await delay(250, signal);
        continue;
      }
      if (predicate(last)) return last;
      await delay(250, signal);
    }
    const error = new Error(`Timed out waiting for ${label}`);
    error.lastProbe = last;
    throw error;
  }

  async #prepareNewChat(session, projectUrl, signal) {
    await session.call('Page.navigate', { url: projectUrl });
    await this.#waitFor(session, (p) => p.readyState === 'complete' || p.readyState === 'interactive', {
      timeoutMs: this.config.navigationTimeoutMs,
      signal,
      label: 'project page',
    });

    let probe = await evaluate(session, probeExpression());
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
      probe = await evaluate(session, probeExpression());
    }
    return this.#waitFor(session, (p) => p.composer, {
      timeoutMs: this.config.navigationTimeoutMs,
      signal,
      label: 'ChatGPT composer',
    });
  }

  async prepare({ projectUrl, prompt, signal }) {
    const target = await this.chromium.createTarget('about:blank');
    const session = await this.chromium.connectTarget(target);
    await session.call('Page.enable');
    await session.call('Runtime.enable');
    await session.call('Network.enable');

    try {
      const before = await this.#prepareNewChat(session, projectUrl, signal);
      if (signal?.aborted) throw signal.reason || new Error('aborted');
      const staged = await evaluate(session, inputExpression(prompt));
      if (staged?.status !== 'READY') {
        throw new Error(`Composer staging failed: ${staged?.status || 'unknown'}`);
      }
      return {
        target,
        session,
        baseline: {
          assistantCount: before.assistantCount || 0,
          assistantTextLength: before.assistantTextLength || 0,
          userCount: before.userCount || 0,
          userTextLength: before.userTextLength || 0,
        },
      };
    } catch (error) {
      session.close();
      await this.chromium.closeTarget(target.id);
      throw error;
    }
  }

  async resume({ conversationUrl, signal }) {
    const expectedId = conversationId(conversationUrl);
    if (!expectedId) throw new Error('canonical conversation URL unavailable for resume');

    const target = await this.chromium.createTarget(conversationUrl);
    const session = await this.chromium.connectTarget(target);
    await session.call('Page.enable');
    await session.call('Runtime.enable');
    await session.call('Network.enable');

    try {
      const probe = await this.#waitFor(session, (p) => p.loginPage
        || ((p.readyState === 'complete' || p.readyState === 'interactive')
          && conversationId(p.url) === expectedId
          && p.composer), {
        timeoutMs: this.config.navigationTimeoutMs,
        signal,
        label: 'existing conversation',
      });
      if (probe.loginPage) {
        const error = new Error('ChatGPT browser profile requires sign-in');
        error.code = 'AUTH_REQUIRED';
        throw error;
      }
      return {
        target,
        session,
        baseline: {
          assistantCount: Math.max(0, Number(probe.assistantCount || 0) - 1),
          assistantTextLength: 0,
          userCount: probe.userCount || 0,
          userTextLength: probe.userTextLength || 0,
        },
      };
    } catch (error) {
      session.close();
      await this.chromium.closeTarget(target.id);
      throw error;
    }
  }

  async #submitWithProfile({ session, profileOperations, signal }) {
    if (signal?.aborted) throw signal.reason || new Error('aborted');

    const sendReadyStarted = Date.now();
    let sendReady = null;
    while (Date.now() - sendReadyStarted < 5000) {
      if (signal?.aborted) throw signal.reason || new Error('aborted');
      sendReady = await evaluate(session, sendReadyExpression());
      if (sendReady?.ready) break;
      await delay(150, signal);
    }
    if (!sendReady?.ready) {
      throw new Error(`Composer send control not ready: ${sendReady?.status || 'unknown'}`);
    }

    await session.call('Fetch.enable', {
      patterns: [
        { urlPattern: 'https://chatgpt.com/backend-api/conversation', requestStage: 'Request' },
        { urlPattern: 'https://chatgpt.com/backend-api/f/conversation', requestStage: 'Request' },
        { urlPattern: 'https://chatgpt.com/backend-api/conversation/init', requestStage: 'Request' },
      ],
    });

    let settled = false;
    let resolveCanonical;
    let rejectCanonical;
    const canonical = new Promise((resolve, reject) => {
      resolveCanonical = resolve;
      rejectCanonical = reject;
    });

    const off = session.on('Fetch.requestPaused', async (params) => {
      const request = params.request || {};
      try {
        if (!isConversationRequest(request.url, request.method)) {
          await session.call('Fetch.continueRequest', { requestId: params.requestId });
          return;
        }
        let body;
        try {
          body = JSON.parse(String(request.postData || ''));
        } catch {
          throw new Error('Conversation request body is not valid JSON');
        }
        const patched = JSON.stringify(applyProfileOperations(body, profileOperations));
        await session.call('Fetch.continueRequest', {
          requestId: params.requestId,
          postData: Buffer.from(patched, 'utf8').toString('base64'),
        });
        if (!settled) {
          settled = true;
          resolveCanonical({ url: request.url });
        }
      } catch (error) {
        try {
          await session.call('Fetch.failRequest', {
            requestId: params.requestId,
            errorReason: 'Aborted',
          });
        } catch {}
        if (!settled) {
          settled = true;
          rejectCanonical(error);
        }
      }
    });

    try {
      const sent = await evaluate(session, submitExpression());
      if (sent?.status !== 'SUBMITTED') {
        throw new Error(`Composer send failed: ${sent?.status || 'unknown'}`);
      }
      const timeout = new Promise((_, reject) => {
        const timer = setTimeout(() => reject(new Error('Canonical conversation POST timeout')), 10000);
        canonical.finally(() => clearTimeout(timer)).catch(() => {});
      });
      await Promise.race([canonical, timeout]);
      return await this.#waitFor(session, (p) => !!conversationId(p.url), {
        timeoutMs: this.config.navigationTimeoutMs,
        signal,
        label: 'canonical conversation URL',
      });
    } finally {
      off();
      try { await session.call('Fetch.disable'); } catch {}
    }
  }

  async submitPrepared({ session, profileOperations, signal }) {
    return this.#submitWithProfile({ session, profileOperations, signal });
  }

  async livenessSnapshot({ session, signal }) {
    if (signal?.aborted) throw signal.reason || new Error('aborted');
    return evaluate(session, probeExpression());
  }

  async sendContinuation({ session, prompt, profileOperations, signal }) {
    if (signal?.aborted) throw signal.reason || new Error('aborted');

    const beforeStage = await evaluate(session, probeExpression());
    const expectedId = conversationId(beforeStage.url);
    if (!expectedId) throw new Error('existing conversation URL unavailable');

    const staged = await evaluate(session, inputExpression(prompt));
    if (staged?.status !== 'READY') {
      throw new Error(`Continuation staging failed: ${staged?.status || 'unknown'}`);
    }

    const beforeSubmit = await evaluate(session, probeExpression());
    const sent = await evaluate(session, submitExpression());
    if (sent?.status !== 'SUBMITTED') {
      throw new Error(`Continuation send failed: ${sent?.status || 'unknown'}`);
    }

    const accepted = await this.#waitFor(session, (p) => {
      if (conversationId(p.url) !== expectedId) return false;
      return p.userCount > beforeSubmit.userCount
        || p.userTextLength > beforeSubmit.userTextLength
        || (!!p.userMessageId && p.userMessageId !== beforeSubmit.userMessageId)
        || p.streaming;
    }, {
      timeoutMs: 10000,
      signal,
      label: 'continuation acceptance',
    });

    void profileOperations;
    return accepted;
  }

  async dispatch({ projectUrl, prompt, profileOperations = [], signal, onTransition }) {
    const prepared = await this.prepare({ projectUrl, prompt, signal });
    await onTransition?.('STAGED', {
      targetId: prepared.target.id,
      pageUrl: (await evaluate(prepared.session, probeExpression())).url,
    });
    try {
      const probe = await this.submitPrepared({
        session: prepared.session,
        profileOperations,
        signal,
      });
      await onTransition?.('SENT', {
        targetId: prepared.target.id,
        pageUrl: probe.url,
      });
      return prepared;
    } catch (error) {
      prepared.session.close();
      await this.chromium.closeTarget(prepared.target.id);
      throw error;
    }
  }

  async monitor({ session, baseline, signal, onActivity, livenessGate }) {
    let previous = { ...baseline, streaming: false, paused: false, url: null };
    let lastActivityAt = Date.now();
    let stalled = false;
    let lastReportedStatus = null;
    let lastGateToken = null;

    while (!signal?.aborted) {
      const probe = await evaluate(session, probeExpression());
      const gate = await livenessGate?.() || {};
      const gateState = String(gate.state || 'UNKNOWN');
      const gateEpoch = Number(gate.epoch || 0);
      const gateToken = gateState + ':' + gateEpoch;
      if (gateToken !== lastGateToken) {
        lastGateToken = gateToken;
        lastActivityAt = Date.now();
        stalled = false;
      }
      const livenessSuspended = gateState !== 'RUNNING';
      if (livenessSuspended) {
        lastActivityAt = Date.now();
        stalled = false;
      }
      const changed =
        probe.url !== previous.url ||
        probe.streaming !== previous.streaming ||
        probe.paused !== previous.paused ||
        probe.assistantCount !== previous.assistantCount ||
        probe.assistantTextLength !== previous.assistantTextLength ||
        probe.assistantMessageId !== previous.assistantMessageId ||
        probe.userCount !== previous.userCount ||
        probe.userTextLength !== previous.userTextLength ||
        probe.userMessageId !== previous.userMessageId;

      if (changed) {
        lastActivityAt = Date.now();
        stalled = false;
      }

      const hasResponse =
        probe.assistantCount > baseline.assistantCount ||
        probe.assistantTextLength > baseline.assistantTextLength;
      const completed = hasResponse && !probe.streaming && !probe.paused;
      const isStalled = !livenessSuspended && !completed
        && Date.now() - lastActivityAt >= this.config.stallAfterMs;
      if (isStalled) stalled = true;

      let status = 'RUNNING';
      if (probe.errorText) status = 'PAGE_ERROR';
      else if (completed) status = 'COMPLETED';
      else if (stalled) status = 'STALLED';

      if (changed || status !== lastReportedStatus || completed || probe.errorText) {
        const action = await onActivity?.({
          status,
          pageUrl: probe.url,
          streaming: probe.streaming,
          paused: probe.paused,
          assistantCount: probe.assistantCount,
          assistantTextLength: probe.assistantTextLength,
          assistantMessageId: probe.assistantMessageId,
          userCount: probe.userCount,
          userTextLength: probe.userTextLength,
          userMessageId: probe.userMessageId,
          lastActivityAt: new Date(lastActivityAt).toISOString(),
          pageError: probe.errorText,
        });
        if (action?.resetLiveness) {
          lastActivityAt = Date.now();
          stalled = false;
        }
        lastReportedStatus = status;
      }

      previous = probe;
      if (completed || probe.errorText) return { status, probe, lastActivityAt };
      await delay(this.config.probeIntervalMs, signal);
    }

    return { status: 'ABORTED' };
  }
}
