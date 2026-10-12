// node cdp.mjs '<js expression>' — выполнить JS на открытой странице WebView и вывести результат
const pages = await (await fetch('http://127.0.0.1:9222/json')).json();
const page = pages.find(p => p.type === 'page' && p.url.includes('serialy')) || pages.find(p => p.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise(r => ws.onopen = r);
ws.onmessage = e => {
  const m = JSON.parse(e.data);
  if (m.id === 1) {
    const r = m.result.result;
    console.log(r.value !== undefined ? (typeof r.value === 'string' ? r.value : JSON.stringify(r.value, null, 1)) : JSON.stringify(m.result));
    ws.close();
  }
};
ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: process.argv[2], returnByValue: true, awaitPromise: true } }));
