// node cdpall.mjs '<js>' — выполнить JS во всех фреймах страницы (включая чужие iframe)
const pages = await (await fetch('http://127.0.0.1:9222/json')).json();
const page = pages.find(p => p.type === 'page' && p.url.includes('serialy')) || pages.find(p => p.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise(r => ws.onopen = r);
let id = 0; const pending = new Map(); const contexts = [];
ws.onmessage = e => {
  const m = JSON.parse(e.data);
  if (m.method === 'Runtime.executionContextCreated') contexts.push(m.params.context);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
};
const send = (method, params = {}) => new Promise(r => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
await send('Runtime.enable');
await new Promise(r => setTimeout(r, 500));
for (const c of contexts) {
  const m = await send('Runtime.evaluate', { expression: process.argv[2], contextId: c.id, returnByValue: true });
  const v = m.result?.result?.value;
  if (v !== undefined && v !== null && v !== '') console.log(`[${c.origin} ${c.auxData?.isDefault ? '' : '(isolated)'}]`, typeof v === 'string' ? v : JSON.stringify(v));
}
ws.close();
