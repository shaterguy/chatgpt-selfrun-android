export function normalizeProgressText(value) {
  return String(value || '')
    .replace(/(?:Today|Yesterday|오늘|어제)\s+\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM|오전|오후)?/gi, ' ')
    .replace(/(?:Worked|Working|Thought|Thinking)\s+for\s+\d+(?:\.\d+)?\s*(?:seconds?|minutes?|hours?|s|m|h)(?:\s+\d+(?:\.\d+)?\s*(?:seconds?|minutes?|hours?|s|m|h))*/gi, ' ')
    .replace(/(?:You said:|ChatGPT said:)/gi, ' ')
    .replace(/\s+/g, ' ').trim();
}

const RUNTIME_EVALUATE_TIMEOUT_MS = 5000;
const SUBMIT_POST_GRACE_MS = 5000;

const PAGE_ERROR_PATTERN = [
  'too many requests',
  'could not load this chatgpt conversation',
  'something went wrong',
  'error generating',
  '문제가 발생',
  '오류가 발생',
].join('|');

export function detectPageErrorText(body) {
  const match = String(body || '').match(new RegExp(PAGE_ERROR_PATTERN, 'i'));
  return match ? match[0] : null;
}

export function conversationStateReady(probe) {
  const userMessageId = String(probe?.userMessageId || '').trim();
  const assistantMessageId = String(probe?.assistantMessageId || '').trim();
  const hasResponseState = !!probe?.streaming || !!probe?.paused || !!assistantMessageId
    || Number(probe?.responseTextLength || 0) > 0;
  return !!userMessageId && hasResponseState;
}

export function conversationResumeReady(probe) {
  const userMessageId = String(probe?.userMessageId || '').trim();
  return !!probe?.composer && !!userMessageId;
}

const delay = (ms, signal) => new Promise((resolve, reject) => {
  if (signal?.aborted) return reject(signal.reason || new Error('aborted'));
  const timer = setTimeout(resolve, ms);
  signal?.addEventListener('abort', () => {
    clearTimeout(timer);
    reject(signal.reason || new Error('aborted'));
  }, { once: true });
});

async function evaluate(session, expression, timeoutMs = RUNTIME_EVALUATE_TIMEOUT_MS) {
  const result = await session.call('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  }, { timeoutMs });
  if (result.exceptionDetails) {
    const detail = result.exceptionDetails.exception?.description
      || result.exceptionDetails.exception?.value
      || result.exceptionDetails.text
      || 'Browser script evaluation failed';
    throw new Error(String(detail));
  }
  return result.result?.value;
}

function runtimeEvaluateTimedOut(error) {
  return String(error?.message || error) === 'CDP command timeout: Runtime.evaluate';
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


const DECORATIVE_ANIMATION_PAUSE_SOURCE = "(() => {" +
  "const id='__selfrun_decorative_animation_pause';" +
  "const css=[" +
    "'[class*=\\\"LoadingResultsShimmer\\\"]'," +
    "'[class*=\\\"LoadingResultsShimmer\\\"]::before'," +
    "'[class*=\\\"LoadingResultsShimmer\\\"]::after'," +
    "'.loading-shimmer-pure-text'," +
    "'.loading-shimmer-pure-text::before'," +
    "'.loading-shimmer-pure-text::after'," +
    "'.pulsing-dot'," +
    "'.pulsing-dot::before'," +
    "'.pulsing-dot::after'" +
  "].join(',')+'{animation-play-state:paused !important;}';" +
  "const install=()=>{" +
    "if(document.getElementById(id))return true;" +
    "const style=document.createElement('style');" +
    "style.id=id;style.textContent=css;" +
    "(document.head||document.documentElement).appendChild(style);" +
    "return true;" +
  "};" +
  "if(document.documentElement)return install();" +
  "document.addEventListener('DOMContentLoaded',install,{once:true});" +
  "return false;" +
"})()";

async function installDecorativeAnimationPause(session) {
  await session.call('Page.addScriptToEvaluateOnNewDocument', {
    source: DECORATIVE_ANIMATION_PAUSE_SOURCE,
  });
  await evaluate(session, DECORATIVE_ANIMATION_PAUSE_SOURCE);
}

export function effectiveStopButtonVisible({ rawStopButtonVisible, inConversation, userCount, assistantCount, responseTurnId }) {
  return Boolean(rawStopButtonVisible && (inConversation || userCount > 0 || assistantCount > 0 || responseTurnId));
}

function probeExpression() {
  const errorPattern = JSON.stringify(PAGE_ERROR_PATTERN);
  return `(() => {
    const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;
    const buttons=[...document.querySelectorAll('button')].filter(visible);
    const selectors=['textarea#prompt-textarea','textarea[data-testid="prompt-textarea"]',
      'div#prompt-textarea[contenteditable="true"]','main form [contenteditable="true"][data-lexical-editor="true"]',
      'main form [contenteditable="true"]'];
    let composer=null;
    for(const selector of selectors){composer=[...document.querySelectorAll(selector)].find(visible);if(composer)break;}
    const rawStopButtonVisible=buttons.some(b=>b.dataset.testid==='stop-button'||/stop|중지/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')));
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
    const normalizeProgressText=${normalizeProgressText.toString()};
    const fingerprint=value=>{
      const input=String(value||'');
      if(!input)return '';
      let hash=2166136261;
      for(let i=0;i<input.length;i+=1){hash^=input.charCodeAt(i);hash=Math.imul(hash,16777619);}
      return (hash>>>0).toString(16).padStart(8,'0');
    };
    const turns=[...document.querySelectorAll('main [data-turn-key]')];
    const responseTurn=turns.length?turns[turns.length-1]:null;
    const responseTurnId=String(responseTurn?.getAttribute?.('data-turn-key')||'').trim()||null;
    const pathParts=location.pathname.split('/').filter(Boolean);
    const inConversation=pathParts.includes('c')&&pathParts.indexOf('c')+1<pathParts.length;
    const effectiveStopButtonVisible=${effectiveStopButtonVisible.toString()};
    const stopButtonVisible=effectiveStopButtonVisible({
      rawStopButtonVisible,
      inConversation,
      userCount:users.length,
      assistantCount:assistants.length,
      responseTurnId,
    });
    let responseText='';
    if(responseTurn){
      const excludedSelector=[
        '[data-user-message-bubble]',
        '[data-message-author-role="user"]',
        '[data-chatgpt-search-unit-key$=":user"]',
        '[data-content-search-unit-key$=":user"]',
        'form','textarea','input','select','time','script','style','noscript','svg',
        '[data-markdown-copy="exclude"]','[aria-hidden="true"]','.sr-only'
      ].join(',');
      const parts=[];
      const walker=document.createTreeWalker(responseTurn,NodeFilter.SHOW_TEXT);
      while(walker.nextNode()){
        const node=walker.currentNode;
        const parent=node.parentElement;
        if(!parent||parent.closest(excludedSelector))continue;
        const button=parent.closest('button');
        if(button){
          const label=String(button.getAttribute('aria-label')||button.title||button.innerText||'');
          if(/copy|good response|bad response|share|regenerate|retry|more|복사|공유|다시 생성|재시도|더보기/i.test(label))continue;
        }
        let hidden=false;
        for(let el=parent;el&&el!==responseTurn.parentElement;el=el.parentElement){
          const style=getComputedStyle(el);
          if(style.display==='none'||style.visibility==='hidden'||style.opacity==='0'){hidden=true;break;}
        }
        if(hidden)continue;
        const value=String(node.nodeValue||'').trim();
        if(value)parts.push(value);
      }
      responseText=normalizeProgressText(parts.join(' '));
    }
    const responseTextLength=responseText.length;
    const responseFingerprint=fingerprint(responseText);
    const body=String(document.body?.innerText||'');
    const errorMatch=body.match(new RegExp(${errorPattern},'i'));
    return {
      url:location.href,
      title:document.title,
      readyState:document.readyState,
      loginPage:(()=>{const p=String(location.pathname||'').toLowerCase();return p==='/auth'||p.startsWith('/auth/')||p==='/login'||p.startsWith('/login/');})(),
      composer:!!composer,
      streaming:stopButtonVisible,
      stopButtonVisible,
      paused,
      assistantCount:assistants.length,
      assistantTextLength:String(last?.innerText||last?.textContent||'').length,
      assistantMessageId:messageId(last),
      responseTurnId,
      responseTextLength,
      responseFingerprint,
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
        if(/websocket.*closed|websocket.*not open|ECONNREFUSED/i.test(String(error?.message||error))) {
          error.code='BROWSER_DEAD';throw error;
        }
        last = { probeError: String(error?.message || error) };
        await delay(250, signal);
        continue;
      }
      if (predicate(last)) return last;
      await delay(250, signal);
    }
    const error = new Error(`Timed out waiting for ${label}`);
    error.lastProbe = last;
    error.code=last?.probeError?'ATTACH_FAILED':'CONVERSATION_LOAD_FAILED';
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
    if(conversationId(probe.url))throw Object.assign(new Error('new conversation could not be established'),{code:'NEW_CONVERSATION_REQUIRED'});
    return this.#waitFor(session, (p) => p.composer&&!conversationId(p.url), {
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
    await installDecorativeAnimationPause(session);

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
          responseTurnId: before.responseTurnId || null,
          responseTextLength: before.responseTextLength || 0,
          responseFingerprint: before.responseFingerprint || '',
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

    const targets=await this.chromium.listExistingTargets();
    const existing=targets?.find(t=>t.type==='page'&&conversationId(t.url)===expectedId);
    const target = existing || await this.chromium.createTarget(conversationUrl);
    const session = await this.chromium.connectTarget(target);
    await session.call('Page.enable');
    await session.call('Runtime.enable');
    await session.call('Network.enable');
    await installDecorativeAnimationPause(session);

    try {
      const probe = await this.#waitFor(session, (p) => p.loginPage
        || ((p.readyState === 'complete' || p.readyState === 'interactive')
          && conversationId(p.url) === expectedId
          && conversationResumeReady(p)) || !!p.errorText, {
        timeoutMs: this.config.navigationTimeoutMs,
        signal,
        label: 'existing conversation',
      });
      if (probe.loginPage) {
        const error = new Error('ChatGPT browser profile requires sign-in');
        error.code = 'AUTH_REQUIRED';
        throw error;
      }
      if(probe.errorText)throw Object.assign(new Error('conversation load failed'),{code:'CONVERSATION_LOAD_FAILED'});
      return {
        target,
        session,
        baseline: {
          assistantCount: Math.max(0, Number(probe.assistantCount || 0) - 1),
          assistantTextLength: 0,
          responseTurnId: probe.responseTurnId || null,
          responseTextLength: probe.responseTextLength || 0,
          responseFingerprint: probe.responseFingerprint || '',
          userCount: probe.userCount || 0,
          userTextLength: probe.userTextLength || 0,
        },
      };
    } catch (error) {
      session.close();
      if(!existing) await this.chromium.closeTarget(target.id);
      throw error;
    }
  }

  async #submitWithProfile({ session, profileOperations, signal, waitForMessagePost = false, guard = () => true, onRequest = null, conversationUrl = '' }) {
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
    let released = false;
    const pausedRequests=new Map();
    let pendingError=null;
    let closed = false;
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
        if(closed||signal?.aborted||!guard()) throw new Error('stale canonical POST callback');
        const expected=conversationId(conversationUrl);
        if(expected&&body.conversation_id&&body.conversation_id!==expected) throw new Error('POST conversation ownership mismatch');
        const initializationRequest = new URL(request.url).pathname.toLowerCase().replace(/\/+$/, '') === '/backend-api/conversation/init';
        const entry={messageRequest:!initializationRequest,continued:false,cancelled:false};
        pausedRequests.set(params.requestId,entry);
        const message=(body.messages||[]).find(m=>m.author?.role==='user');
        if(message) await onRequest?.({message_id:message.id||null,released:true});
        if(closed||signal?.aborted||!guard()) throw new Error('stale canonical POST callback');
        const patched = JSON.stringify(applyProfileOperations(body, profileOperations));
        entry.continued=true;
        if(entry.messageRequest)released=true;
        await session.call('Fetch.continueRequest', {
          requestId: params.requestId,
          postData: Buffer.from(patched, 'utf8').toString('base64'),
        });

        if (!settled && (!waitForMessagePost || !initializationRequest)) {
          settled = true;
          resolveCanonical({ url: request.url });
        }
      } catch (error) {
        try {
          await session.call('Fetch.failRequest', {
            requestId: params.requestId,
            errorReason: 'Aborted',
          });
          const entry=pausedRequests.get(params.requestId);if(entry&&!entry.continued)entry.cancelled=true;
        } catch {}
        if (!settled) {
          settled = true;
          rejectCanonical(error);
        }
      }
    });

    try {
      const submitOutcome = evaluate(session, submitExpression()).then(
        (value) => ({ type: 'evaluate', value }),
        (error) => ({ type: 'evaluate-error', error }),
      );
      const canonicalOutcome = canonical.then(
        (value) => ({ type: 'canonical', value }),
        (error) => ({ type: 'canonical-error', error }),
      );
      const first = await Promise.race([submitOutcome, canonicalOutcome]);
      let uncertainSubmitError = null;
      if (first.type === 'evaluate') {
        if (first.value?.status !== 'SUBMITTED') {
          throw new Error(`Composer send failed: ${first.value?.status || 'unknown'}`);
        }
      } else if (first.type === 'evaluate-error') {
        if (!runtimeEvaluateTimedOut(first.error)) throw first.error;
        uncertainSubmitError = first.error;
      } else if (first.type === 'canonical-error') {
        throw first.error;
      }

      if (first.type !== 'canonical') {
        const canonicalTimeoutMs = uncertainSubmitError ? SUBMIT_POST_GRACE_MS : 10000;
        const timeout = new Promise((_, reject) => {
          const timer = setTimeout(
            () => reject(new Error('Canonical conversation POST timeout')),
            canonicalTimeoutMs,
          );
          canonical.finally(() => clearTimeout(timer)).catch(() => {});
        });
        try {
          await Promise.race([canonical, timeout]);
        } catch (error) {
          if (uncertainSubmitError
              && String(error?.message || error) === 'Canonical conversation POST timeout') {
            throw uncertainSubmitError;
          }
          throw error;
        }
      }
      return await this.#waitFor(session, (p) => !!conversationId(p.url), {
        timeoutMs: this.config.navigationTimeoutMs,
        signal,
        label: 'canonical conversation URL',
      });
    } catch(error) {
      pendingError=error;
      throw error;
    } finally {
      closed=true;
      for(const [id,entry] of pausedRequests)if(!entry.continued&&!entry.cancelled) {
        try {await session.call('Fetch.failRequest',{requestId:id,errorReason:'Aborted'});entry.cancelled=true;}catch{}
      }
      if(pendingError) {
        const proven=!released&&[...pausedRequests.values()].some(e=>e.messageRequest&&e.cancelled)
          &&[...pausedRequests.values()].every(e=>!e.messageRequest||e.cancelled);
        pendingError.released=released;
        pendingError.absenceProof=proven?'INTERCEPTED_REQUEST_ABORTED':null;
      }
      off();
      try { await session.call('Fetch.disable'); } catch {}
    }
  }

  async livenessSnapshot({ session, signal }) {
    if (signal?.aborted) throw signal.reason || new Error('aborted');
    return evaluate(session, probeExpression());
  }

  async attachTarget({targetId,signal}) {
    const targets=await this.chromium.listExistingTargets();
    const target=targets?.find(t=>t.id===targetId&&t.type==='page');
    if(!target)throw Object.assign(new Error('original submission target unavailable'),{code:'ATTACH_FAILED'});
    const session=await this.chromium.connectTarget(target);
    if(signal?.aborted){session.close();throw signal.reason;}
    return {target,session,baseline:await evaluate(session,probeExpression())};
  }

  async findSubmissionAcrossTargets({intent,prompt,signal}) {
    if(signal?.aborted)throw signal.reason;
    if(!intent?.message_id||!this.chromium?.listExistingTargets)return {state:'UNKNOWN'};
    const expected=JSON.stringify(String(prompt).replace(/\s+/g,' ').trim());
    const messageId=JSON.stringify(intent.message_id||'');
    const expression='(() => {const expected='+expected+', id='+messageId+';'+
      'const users=[...document.querySelectorAll(\'[data-message-author-role="user"],[data-chatgpt-search-unit-key$=":user"],[data-content-search-unit-key$=":user"]\')];'+
      'const matched=users.map(e=>{let found=false;'+
      'for(let n=e,depth=0;n&&depth<6;n=n.parentElement,depth++){'+
      'const ids=["data-message-id","data-chatgpt-search-unit-key","data-content-search-unit-key","data-turn-key"].map(k=>String(n.getAttribute?.(k)||""));'+
      'if(id&&ids.some(v=>v===id||v===id+":user"))found=true;}'+
      'const same=String(e.innerText||e.textContent||"").replace(/\\s+/g," ").trim()===expected;return {found,same};});'+
      'return {idMatch:matched.some(m=>m.found),lastTextMatch:matched.at(-1)?.same||false};})()';
    const targets=await this.chromium.listExistingTargets();
    for(const target of targets||[]) {
      if(target?.type!=='page'||!target?.webSocketDebuggerUrl)continue;
      let candidate;
      try {
        candidate=await this.chromium.connectTarget(target);
        let probe=await evaluate(candidate,probeExpression());
        if(!conversationId(probe.url))continue;
        await candidate.call('Page.reload',{ignoreCache:true});
        probe=await this.#waitFor(candidate,p=>p.loginPage||((p.readyState==='complete'||p.readyState==='interactive')
          &&p.composer&&!!conversationId(p.url)),{timeoutMs:this.config.navigationTimeoutMs,signal,label:'cross-target submission recovery'});
        if(probe.loginPage||!conversationId(probe.url))continue;
        const evidence=await evaluate(candidate,expression);
        if(evidence.idMatch&&evidence.lastTextMatch)
          return {state:'CONFIRMED',probe,messageId:intent.message_id};
      } catch(error) {
        if(signal?.aborted)throw error;
      } finally {
        try {candidate?.close();} catch {}
      }
    }
    return {state:'UNKNOWN'};
  }

  async submitIntent({session,prompt,kind,intent,conversationUrl,profileOperations,signal,guard,onRequest}) {
    if(signal?.aborted||!guard())throw new Error('stale submission');
    const probe=await evaluate(session,probeExpression());
    if(conversationUrl&&conversationId(probe.url)!==conversationId(conversationUrl))
      throw new Error('submission conversation ownership mismatch');
    if(kind==='initial'&&conversationId(probe.url))throw new Error('initial submission cannot target an existing conversation');
    const staged=await evaluate(session,inputExpression(prompt));
    if(staged?.status!=='READY')throw new Error('submission composer not ready');
    if(signal?.aborted||!guard())throw new Error('stale submission');
    return this.#submitWithProfile({session,profileOperations,signal,guard,onRequest,conversationUrl,waitForMessagePost:true});
  }

  async readSubmissionAcrossTargets({intent,prompt,signal}) {
    if(signal?.aborted)throw signal.reason;
    if(!intent?.message_id||!this.chromium?.listExistingTargets)return {state:'UNKNOWN',probe:null};
    const expected=JSON.stringify(String(prompt).replace(/\s+/g,' ').trim());
    const messageId=JSON.stringify(intent.message_id);
    const expression='(() => {const expected='+expected+', id='+messageId+';'+
      'const users=[...document.querySelectorAll(\'[data-message-author-role="user"],[data-chatgpt-search-unit-key$=":user"],[data-content-search-unit-key$=":user"]\')];'+
      'const matched=users.map(e=>{let found=false;'+
      'for(let n=e,depth=0;n&&depth<6;n=n.parentElement,depth++){'+
      'const ids=["data-message-id","data-chatgpt-search-unit-key","data-content-search-unit-key","data-turn-key"].map(k=>String(n.getAttribute?.(k)||""));'+
      'if(id&&ids.some(v=>v===id||v===id+":user"))found=true;}'+
      'const same=String(e.innerText||e.textContent||"").replace(/\\s+/g," ").trim()===expected;return {found,same};});'+
      'return {idMatch:matched.some(m=>m.found),lastTextMatch:matched.at(-1)?.same||false};})()';
    const targets=await this.chromium.listExistingTargets();
    for(const target of targets||[]) {
      if(target?.type!=='page'||!target?.webSocketDebuggerUrl)continue;
      let candidate;
      try {
        candidate=await this.chromium.connectTarget(target);
        let probe=await evaluate(candidate,probeExpression());
        if(!conversationId(probe.url))continue;
        await candidate.call('Page.reload',{ignoreCache:true});
        probe=await this.#waitFor(candidate,p=>p.loginPage||((p.readyState==='complete'||p.readyState==='interactive')
          &&p.composer&&!!conversationId(p.url)),{timeoutMs:this.config.navigationTimeoutMs,signal,label:'cross-target submission readback'});
        if(probe.loginPage||!conversationId(probe.url))continue;
        const evidence=await evaluate(candidate,expression);
        if(evidence.idMatch&&evidence.lastTextMatch)
          return {state:'CONFIRMED',probe,messageId:intent.message_id};
      } catch(error) {
        if(signal?.aborted)throw error;
      } finally {
        try {candidate?.close();} catch {}
      }
    }
    return {state:'UNKNOWN',probe:null};
  }

  async readSubmission({session,intent,prompt,conversationUrl,signal}) {
    if(signal?.aborted)throw signal.reason;
    let probe=await evaluate(session,probeExpression());
    const expectedId=conversationId(conversationUrl)||conversationId(probe.url);
    if(conversationUrl&&conversationId(probe.url)!==expectedId)return {state:'UNKNOWN',probe};
    // Optimistic DOM bubbles do not prove server acceptance. Reload the canonical page before classifying.
    try {
      await session.call('Page.reload',{ignoreCache:true});
      probe=await this.#waitFor(session,p=>p.loginPage||((p.readyState==='complete'||p.readyState==='interactive')&&p.composer
        &&(!expectedId||conversationId(p.url)===expectedId)),{timeoutMs:this.config.navigationTimeoutMs,signal,label:'submission readback'});
      if(probe.loginPage)return {state:'UNKNOWN',probe};
    }catch(error){if(signal?.aborted)throw error;return {state:'UNKNOWN',probe};}
    if(conversationUrl&&conversationId(probe.url)!==conversationId(conversationUrl))
      return {state:'UNKNOWN',probe};
    const expected=JSON.stringify(String(prompt).replace(/\s+/g,' ').trim());
    const messageId=JSON.stringify(intent.message_id||'');
    const expression='(() => {const expected='+expected+', id='+messageId+';'+
      'const users=[...document.querySelectorAll(\'[data-message-author-role="user"],[data-chatgpt-search-unit-key$=":user"],[data-content-search-unit-key$=":user"]\')];'+
      'const matched=users.map(e=>{let found=false;'+
      'for(let n=e,depth=0;n&&depth<6;n=n.parentElement,depth++){'+
      'const ids=["data-message-id","data-chatgpt-search-unit-key","data-content-search-unit-key","data-turn-key"].map(k=>String(n.getAttribute?.(k)||""));'+
      'if(id&&ids.some(v=>v===id||v===id+":user"))found=true;}'+
      'const same=String(e.innerText||e.textContent||"").replace(/\\s+/g," ").trim()===expected;return {found,same};});'+
      'return {idMatch:matched.some(m=>m.found),lastTextMatch:matched.at(-1)?.same||false};})()';
    const evidence=await evaluate(session,expression);
    const hasBaseline=Number.isFinite(intent.baseline?.userCount)||!!intent.baseline?.userMessageId;
    const advanced=hasBaseline&&(Number(probe.userCount)>Number(intent.baseline?.userCount||0)||
      (!!probe.userMessageId&&probe.userMessageId!==intent.baseline?.userMessageId));
    if(conversationId(probe.url)&&(evidence.idMatch||(advanced&&evidence.lastTextMatch)))
      return {state:'CONFIRMED',probe,messageId:intent.message_id||probe.userMessageId};
    if(!conversationUrl&&intent.message_id) {
      const crossTarget=await this.readSubmissionAcrossTargets({intent,prompt,signal});
      if(crossTarget.state==='CONFIRMED')return crossTarget;
    }
    // A request released to ChatGPT may arrive later; a quiet page alone cannot prove absence.
    if(intent.absence_proof==='INTERCEPTED_REQUEST_ABORTED'&&!intent.released&&!advanced&&!probe.streaming&&probe.readyState==='complete'&&probe.composer)
      return {state:'ABSENT',probe};
    return {state:'UNKNOWN',probe};
  }

  async quiesce({session,conversationUrl,signal,guard}) {
    const before=await evaluate(session,probeExpression());
    if(conversationId(before.url)!==conversationId(conversationUrl))throw new Error('quiesce conversation mismatch');
    if(!before.streaming&&!before.stopButtonVisible)return before;
    if(signal?.aborted||!guard())throw new Error('stale stream cleanup');
    await evaluate(session,'(() => {'+
      'const buttons=[...document.querySelectorAll("button")].filter(e=>e.isConnected&&e.offsetParent!==null);'+
      'const stop=buttons.find(b=>b.dataset.testid==="stop-button"||/stop|중지/i.test((b.getAttribute("aria-label")||"")+" "+(b.title||"")));'+
      'if(stop)stop.click();return !!stop;})()');
    return this.#waitFor(session,p=>!p.streaming&&!p.stopButtonVisible,{
      timeoutMs:this.config.navigationTimeoutMs,signal,label:'stuck stream cleanup'});
  }

  async monitor({ session, baseline, signal, onActivity, livenessGate }) {
    let previous = { ...baseline, streaming: false, paused: false, url: null };
    let lastActivityAt = Date.now();
    let stalled = false;
    let lastReportedStatus = null;
    let lastGateToken = null;
    let nextStalledVerificationAt = 0;
    let evaluateTimeoutRetries = 0;
    let conversationStateMissingSince = 0;

    while (!signal?.aborted) {
      let probe;
      try {
        probe = await evaluate(session, probeExpression());
        evaluateTimeoutRetries = 0;
      } catch (error) {
        if (signal?.aborted) throw signal.reason || new Error('aborted');
        const message = String(error?.message || error);
        if (message !== 'CDP command timeout: Runtime.evaluate'
            || evaluateTimeoutRetries >= 3) {
          throw error;
        }
        evaluateTimeoutRetries += 1;
        await delay(this.config.probeIntervalMs, signal);
        continue;
      }
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
      const progressDetected =
        probe.assistantCount !== previous.assistantCount ||
        probe.assistantTextLength !== previous.assistantTextLength ||
        probe.assistantMessageId !== previous.assistantMessageId ||
        probe.responseTurnId !== previous.responseTurnId ||
        probe.responseTextLength !== previous.responseTextLength ||
        probe.responseFingerprint !== previous.responseFingerprint;
      const changed =
        probe.url !== previous.url ||
        probe.paused !== previous.paused ||
        progressDetected;

      if (changed) {
        lastActivityAt = Date.now();
        stalled = false;
      }

      const structurallyReady = conversationStateReady(probe);
      const resumeReady = conversationResumeReady(probe);
      const conversationAvailable = structurallyReady || resumeReady;
      if (conversationAvailable) conversationStateMissingSince = 0;
      else if (!conversationStateMissingSince) conversationStateMissingSince = Date.now();
      const stateGraceMs = Math.max(0, Number(this.config.conversationStateGraceMs ?? 5000));
      const conversationStateUnavailable = !livenessSuspended
        && conversationStateMissingSince > 0
        && Date.now() - conversationStateMissingSince >= stateGraceMs;

      // ChatGPT response lifecycle is telemetry only. It must never terminate a SelfRun dispatch.
      const isStalled = !livenessSuspended
        && Date.now() - lastActivityAt >= this.config.stallAfterMs;
      if (isStalled) stalled = true;

      const structuralError = conversationStateUnavailable ? 'Conversation state unavailable' : null;
      const pageError = conversationAvailable ? null : (probe.errorText || structuralError);
      let status = 'RUNNING';
      if (pageError) status = 'PAGE_ERROR';
      else if (stalled) status = 'STALLED';

      const verificationRetry = status === 'STALLED'
        && nextStalledVerificationAt > 0
        && Date.now() >= nextStalledVerificationAt;
      { // Every observation permits Result reconciliation even when UI telemetry is stale.
        const action = await onActivity?.({
          status,
          pageUrl: probe.url,
          streaming: probe.streaming,
          stopButtonVisible: probe.stopButtonVisible,
          paused: probe.paused,
          assistantCount: probe.assistantCount,
          assistantTextLength: probe.assistantTextLength,
          assistantMessageId: probe.assistantMessageId,
          responseTurnId: probe.responseTurnId,
          responseTextLength: probe.responseTextLength,
          responseFingerprint: probe.responseFingerprint,
          userCount: probe.userCount,
          userTextLength: probe.userTextLength,
          userMessageId: probe.userMessageId,
          lastActivityAt: new Date(lastActivityAt).toISOString(),
          pageError,
          conversationStateReady: structurallyReady,
          verificationRetry,
          progressDetected,
        });
        if (action?.resetLiveness) {
          lastActivityAt = Date.now();
          stalled = false;
          nextStalledVerificationAt = 0;
        } else if (status === 'STALLED' && Number(action?.retryAfterMs) > 0) {
          nextStalledVerificationAt = Date.now() + Number(action.retryAfterMs);
        } else if (status !== 'STALLED') {
          nextStalledVerificationAt = 0;
        }
        lastReportedStatus = status;
      }

      previous = probe;
      if (pageError) {
        return {
          status,
          probe: { ...probe, errorText: pageError },
          lastActivityAt,
        };
      }
      await delay(this.config.probeIntervalMs, signal);
    }

    return { status: 'ABORTED' };
  }
}
