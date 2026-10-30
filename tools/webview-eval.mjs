#!/usr/bin/env node
// Evaluate JS in a debuggable app's live WebView via the DevTools protocol (no Chrome window needed).
//   adb -d forward tcp:9222 localabstract:webview_devtools_remote_$(adb -d shell pidof br.com.roqlab.caderneta.debug)
//   tools/webview-eval.mjs 'document.title'          # first page target on localhost:9222
const expr = process.argv[2] ?? "location.href";
const targets = await (await fetch("http://localhost:9222/json")).json();
const page = targets.find((t) => t.type === "page");
if (!page) throw new Error("no WebView page; is the screen open and the port forwarded?");
const sock = new WebSocket(page.webSocketDebuggerUrl);
sock.onopen = () =>
  sock.send(JSON.stringify({ id: 1, method: "Runtime.evaluate", params: { expression: expr, returnByValue: true, awaitPromise: true } }));
sock.onmessage = (m) => {
  const r = JSON.parse(m.data);
  if (r.id !== 1) return;
  console.log(JSON.stringify(r.result.result.value ?? r.result, null, 1));
  sock.close();
};
