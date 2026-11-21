import http from 'node:http';
import { CdpSession } from '/data/data/com.termux/files/home/work/selfrun-server-v4.0.0-dev44-release/source-checkout/termux-server/src/browser/cdp.mjs';

const HOST = '127.0.0.1';
const PORT = 17832;
const DEBUG = 'http://127.0.0.1:19228';
let session = null;
let targetId = null;

async function target() {
  const response = await fetch(DEBUG + '/json/list');
  const list = await response.json();
  const pages = list.filter((x) => x.type === 'page' && !String(x.url || '').startsWith('devtools://'));
  return pages.find((x) => !String(x.url || '').startsWith('about:')) || pages[0];
}

async function cdp() {
  const t = await target();
  if (!t) throw new Error('No Chromium page target');
  if (!session || targetId !== t.id) {
    try { session?.close(); } catch {}
    session = await new CdpSession(t.webSocketDebuggerUrl).connect();
    targetId = t.id;
    await session.call('Page.enable');
    await session.call('Runtime.enable');
  }
  return session;
}
async function body(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return Buffer.concat(chunks).toString('utf8');
}

async function key(name) {
  const s = await cdp();
  const map = {
    Enter: { key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 },
    Tab: { key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 },
    Backspace: { key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 },
    Escape: { key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 },
  };
  const k = map[name];
  if (!k) throw new Error('Unsupported key');
  await s.call('Input.dispatchKeyEvent', { type: 'keyDown', ...k });
  await s.call('Input.dispatchKeyEvent', { type: 'keyUp', ...k });
}

function send(res, status, type, data) {
  const buf = Buffer.isBuffer(data) ? data : Buffer.from(String(data));
  res.writeHead(status, { 'content-type': type, 'content-length': buf.length, 'cache-control': 'no-store' });
  res.end(buf);
}
const page = `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SelfRun Login</title>
<style>
body{font-family:sans-serif;margin:8px;background:#111;color:#eee}button,input,textarea{font-size:16px;margin:4px}
#screen{width:100%;height:auto;border:1px solid #555;touch-action:manipulation}
textarea{width:96%;height:70px}#status{font-size:13px;word-break:break-all}
</style>
<div id="status">loading...</div>
<div id="controls"></div>
<img id="screen" alt="Chromium">
<textarea id="text" placeholder="여기에 입력하거나 붙여넣은 뒤 전송"></textarea><br>
<button onclick="act('login')">로그인</button>
<button onclick="act('field')">현재 입력칸</button>
<button onclick="act('google')">Google 로그인</button>
<button onclick="act('continue')">계속</button>
<button onclick="sendText()">문자 전송</button>
<button onclick="sendKey('Enter')">Enter</button>
<button onclick="sendKey('Tab')">Tab</button>
<button onclick="sendKey('Backspace')">Backspace</button>
<button onclick="scrollPage(-600)">위로</button>
<button onclick="scrollPage(600)">아래로</button>
<button onclick="refresh()">새로고침</button>
<script>
const img=document.getElementById('screen'), st=document.getElementById('status'), txt=document.getElementById('text'), ctl=document.getElementById('controls');
async function refresh(){img.src='/shot?x='+Date.now(); try{st.textContent=await (await fetch('/status')).text()}catch(e){st.textContent=String(e)}}
img.onclick=async e=>{const r=img.getBoundingClientRect();const x=(e.clientX-r.left)*img.naturalWidth/r.width;const y=(e.clientY-r.top)*img.naturalHeight/r.height;await fetch('/click',{method:'POST',body:JSON.stringify({x,y})});setTimeout(refresh,250)}
async function act(name){await fetch('/action',{method:'POST',body:name});setTimeout(refresh,250)}
async function sendText(){await fetch('/text',{method:'POST',body:txt.value});txt.value='';setTimeout(refresh,250)}
async function sendKey(k){await fetch('/key',{method:'POST',body:k});setTimeout(refresh,250)}
async function scrollPage(d){await fetch('/scroll',{method:'POST',body:String(d)});setTimeout(refresh,250)}
setInterval(refresh,1200);refresh();
</script>`;
const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    if (req.method === 'GET' && url.pathname === '/') return send(res, 200, 'text/html; charset=utf-8', page);
    const s = await cdp();
    if (req.method === 'GET' && url.pathname === '/shot') {
      await s.call('Page.bringToFront');
      const shot = await s.call('Page.captureScreenshot', { format: 'jpeg', quality: 72, fromSurface: true });
      return send(res, 200, 'image/jpeg', Buffer.from(shot.data, 'base64'));
    }
    if (req.method === 'GET' && url.pathname === '/status') {
      const t = await target();
      return send(res, 200, 'text/plain; charset=utf-8', (t?.title || '') + '\n' + (t?.url || ''));
    }
    if (req.method === 'POST' && url.pathname === '/action') {
      const action = (await body(req)).trim();
      const expression = "(() => {" +
        "const action=" + JSON.stringify(action) + ";" +
        "const visible=e=>{const r=e.getBoundingClientRect();const c=getComputedStyle(e);return r.width>0&&r.height>0&&c.display!=='none'&&c.visibility!=='hidden'};" +
        "const text=e=>String(e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('placeholder')||'').replace(/\\s+/g,' ').trim();" +
        "let e=null;" +
        "if(action==='login')e=[...document.querySelectorAll('button,a')].find(x=>visible(x)&&/^(log in|login|로그인)$/i.test(text(x)));" +
        "else if(action==='google')e=[...document.querySelectorAll('button,a')].find(x=>visible(x)&&/google/i.test(text(x)));" +
        "else if(action==='field')e=[...document.querySelectorAll('input,textarea,[contenteditable=true]')].find(x=>visible(x)&&x.getAttribute('type')!=='file');" +
        "else if(action==='continue')e=[...document.querySelectorAll('button')].find(x=>visible(x)&&(x.type==='submit'||/^(continue|계속|next|다음)$/i.test(text(x))));" +
        "if(!e)return {ok:false,action};e.scrollIntoView({block:'center',inline:'center'});e.focus();e.click();return {ok:true,action,tag:e.tagName,type:e.getAttribute('type'),text:text(e)};" +
        "})()";
      const result = await s.call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
      return send(res, 200, 'application/json; charset=utf-8', JSON.stringify(result?.result?.value || { ok: false }));
    }
    if (req.method === 'POST' && url.pathname === '/click') {
      const { x, y } = JSON.parse(await body(req));
      const metrics = await s.call('Runtime.evaluate', { expression: '({dpr:devicePixelRatio||1})', returnByValue: true });
      const dpr = Number(metrics?.result?.value?.dpr) || 1;
      const cssX = x / dpr;
      const cssY = y / dpr;
      await s.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: cssX, y: cssY, button: 'left', clickCount: 1 });
      await s.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: cssX, y: cssY, button: 'left', clickCount: 1 });
      return send(res, 204, 'text/plain', '');
    }
    if (req.method === 'POST' && url.pathname === '/text') {
      await s.call('Input.insertText', { text: await body(req) });
      return send(res, 204, 'text/plain', '');
    }
    if (req.method === 'POST' && url.pathname === '/key') {
      await key((await body(req)).trim());
      return send(res, 204, 'text/plain', '');
    }
    if (req.method === 'POST' && url.pathname === '/scroll') {
      const delta = Number(await body(req)) || 0;
      await s.call('Input.dispatchMouseEvent', { type: 'mouseWheel', x: 540, y: 800, deltaX: 0, deltaY: delta });
      return send(res, 204, 'text/plain', '');
    }
    return send(res, 404, 'text/plain', 'not found');
  } catch (error) {
    return send(res, 500, 'text/plain; charset=utf-8', String(error?.stack || error));
  }
});

server.listen(PORT, HOST, () => {
  console.log(`LOGIN_BRIDGE_READY http://${HOST}:${PORT}/`);
});