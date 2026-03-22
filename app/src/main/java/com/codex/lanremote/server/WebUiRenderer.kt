package com.codex.lanremote.server

import android.content.Context
import com.codex.lanremote.control.ControlCenter

object WebUiRenderer {
    fun render(context: Context): String {
        val baseUrl = ControlCenter.baseUrl(context)
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>LAN Remote Control</title>
              <style>
                body { font-family: sans-serif; margin: 20px; background: #111827; color: #f9fafb; }
                h1, h2 { margin-bottom: 8px; }
                .card { background: #1f2937; padding: 16px; border-radius: 12px; margin-bottom: 16px; }
                .row { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
                button { border: 0; border-radius: 8px; padding: 10px 14px; cursor: pointer; background: #2563eb; color: white; }
                input { border-radius: 8px; border: 1px solid #374151; background: #0f172a; color: white; padding: 10px; min-width: 120px; }
                pre { white-space: pre-wrap; word-break: break-word; background: #020617; padding: 12px; border-radius: 8px; }
                .node { border-top: 1px solid #374151; padding-top: 8px; margin-top: 8px; }
                .muted { color: #93c5fd; }
              </style>
            </head>
            <body>
              <h1>LAN Remote Control</h1>
              <p class="muted">Base URL: $baseUrl</p>

              <div class="card">
                <h2>Status</h2>
                <pre id="statusBox">Loading...</pre>
                <div class="row">
                  <button onclick="refreshAll()">Refresh</button>
                </div>
              </div>

              <div class="card">
                <h2>Global Actions</h2>
                <div class="row">
                  <button onclick="action('back')">Back</button>
                  <button onclick="action('home')">Home</button>
                  <button onclick="action('recents')">Recents</button>
                  <button onclick="action('notifications')">Notifications</button>
                  <button onclick="action('quick_settings')">Quick Settings</button>
                </div>
              </div>

              <div class="card">
                <h2>Tap and Swipe</h2>
                <div class="row">
                  <input id="tapX" placeholder="tap x" value="300">
                  <input id="tapY" placeholder="tap y" value="600">
                  <button onclick="tap()">Tap</button>
                </div>
                <div class="row">
                  <input id="x1" placeholder="x1" value="300">
                  <input id="y1" placeholder="y1" value="1000">
                  <input id="x2" placeholder="x2" value="300">
                  <input id="y2" placeholder="y2" value="300">
                  <input id="duration" placeholder="duration ms" value="300">
                  <button onclick="swipe()">Swipe</button>
                </div>
              </div>

              <div class="card">
                <h2>Input Text</h2>
                <div class="row">
                  <input id="textValue" placeholder="text to set">
                  <button onclick="setText()">Set Text</button>
                </div>
              </div>

              <div class="card">
                <h2>Launch Package</h2>
                <div class="row">
                  <input id="packageName" placeholder="com.android.settings">
                  <button onclick="launchPackage()">Launch</button>
                </div>
              </div>

              <div class="card">
                <h2>Current Nodes</h2>
                <div class="row">
                  <button onclick="refreshNodes()">Refresh Nodes</button>
                </div>
                <div id="nodeList"></div>
              </div>

              <div class="card">
                <h2>Last Response</h2>
                <pre id="logBox"></pre>
              </div>

              <script>
                async function call(path, method = 'POST') {
                  const response = await fetch(path, { method });
                  const data = await response.json();
                  document.getElementById('logBox').textContent = JSON.stringify(data, null, 2);
                  return data;
                }

                async function refreshStatus() {
                  const response = await fetch('/status');
                  const data = await response.json();
                  document.getElementById('statusBox').textContent = JSON.stringify(data, null, 2);
                }

                async function refreshNodes() {
                  const response = await fetch('/nodes');
                  const data = await response.json();
                  const container = document.getElementById('nodeList');
                  container.innerHTML = '';
                  if (!data.nodes) {
                    container.textContent = data.message || 'No nodes';
                    return;
                  }
                  data.nodes.forEach(node => {
                    const div = document.createElement('div');
                    div.className = 'node';

                    const title = document.createElement('div');
                    title.textContent = `${'$'}{node.path} | ${'$'}{node.className} | ${'$'}{node.text || node.contentDescription || '(no text)'}`;
                    div.appendChild(title);

                    const meta = document.createElement('div');
                    meta.textContent = `bounds=${'$'}{node.bounds} clickable=${'$'}{node.clickable} editable=${'$'}{node.editable}`;
                    div.appendChild(meta);

                    const button = document.createElement('button');
                    button.textContent = 'Click Node';
                    button.onclick = () => call(`/node/click?path=${'$'}{encodeURIComponent(node.path)}`);
                    div.appendChild(button);

                    container.appendChild(div);
                  });
                }

                async function refreshAll() {
                  await refreshStatus();
                  await refreshNodes();
                }

                function action(name) {
                  return call(`/action?name=${'$'}{encodeURIComponent(name)}`);
                }

                function tap() {
                  const x = document.getElementById('tapX').value;
                  const y = document.getElementById('tapY').value;
                  return call(`/gesture/tap?x=${'$'}{encodeURIComponent(x)}&y=${'$'}{encodeURIComponent(y)}`);
                }

                function swipe() {
                  const x1 = document.getElementById('x1').value;
                  const y1 = document.getElementById('y1').value;
                  const x2 = document.getElementById('x2').value;
                  const y2 = document.getElementById('y2').value;
                  const duration = document.getElementById('duration').value;
                  return call(`/gesture/swipe?x1=${'$'}{encodeURIComponent(x1)}&y1=${'$'}{encodeURIComponent(y1)}&x2=${'$'}{encodeURIComponent(x2)}&y2=${'$'}{encodeURIComponent(y2)}&duration=${'$'}{encodeURIComponent(duration)}`);
                }

                function setText() {
                  const value = document.getElementById('textValue').value;
                  return call(`/text?value=${'$'}{encodeURIComponent(value)}`);
                }

                function launchPackage() {
                  const packageName = document.getElementById('packageName').value;
                  return call(`/launch?package=${'$'}{encodeURIComponent(packageName)}`);
                }

                refreshAll();
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
