package com.codex.lanremote.server

import android.content.Context
import com.codex.lanremote.control.ControlCenter

object WebUiRenderer {
    fun render(context: Context): String {
        val baseUrl = ControlCenter.baseUrl(context)
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>局域网远程控制</title>
              <style>
                body { font-family: "Microsoft YaHei", "PingFang SC", sans-serif; margin: 20px; background: #111827; color: #f9fafb; }
                h1, h2 { margin-bottom: 8px; }
                .card { background: #1f2937; padding: 16px; border-radius: 12px; margin-bottom: 16px; }
                .row { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
                button { border: 0; border-radius: 8px; padding: 10px 14px; cursor: pointer; background: #2563eb; color: white; }
                input { border-radius: 8px; border: 1px solid #374151; background: #0f172a; color: white; padding: 10px; min-width: 120px; }
                pre { white-space: pre-wrap; word-break: break-word; background: #020617; padding: 12px; border-radius: 8px; }
                .node { border-top: 1px solid #374151; padding-top: 8px; margin-top: 8px; }
                .muted { color: #93c5fd; }
                .screen-wrap { background: #020617; border-radius: 12px; padding: 12px; }
                .screen-view { width: 100%; max-width: 420px; border-radius: 12px; border: 1px solid #374151; display: block; cursor: crosshair; }
              </style>
            </head>
            <body>
              <h1>局域网远程控制</h1>
              <p class="muted">访问地址：$baseUrl</p>

              <div class="card">
                <h2>状态</h2>
                <pre id="statusBox">正在加载...</pre>
                <div class="row">
                  <button onclick="refreshAll()">刷新全部</button>
                </div>
              </div>

              <div class="card">
                <h2>实时画面</h2>
                <div class="screen-wrap">
                  <img id="screenView" class="screen-view" alt="实时画面" />
                </div>
                <div class="row" style="margin-top: 12px;">
                  <button onclick="refreshScreen()">刷新画面</button>
                  <button onclick="toggleAutoScreen()">切换自动刷新</button>
                </div>
                <div class="muted" id="screenTip">提示：点击图片会直接映射成手机点击坐标。</div>
              </div>

              <div class="card">
                <h2>全局操作</h2>
                <div class="row">
                  <button onclick="action('back')">返回</button>
                  <button onclick="action('home')">主页</button>
                  <button onclick="action('recents')">最近任务</button>
                  <button onclick="action('notifications')">通知栏</button>
                  <button onclick="action('quick_settings')">快捷开关</button>
                </div>
              </div>

              <div class="card">
                <h2>坐标点击与滑动</h2>
                <div class="row">
                  <input id="tapX" placeholder="点击 X" value="300">
                  <input id="tapY" placeholder="点击 Y" value="600">
                  <button onclick="tap()">点击</button>
                </div>
                <div class="row">
                  <input id="x1" placeholder="起点 X" value="300">
                  <input id="y1" placeholder="起点 Y" value="1000">
                  <input id="x2" placeholder="终点 X" value="300">
                  <input id="y2" placeholder="终点 Y" value="300">
                  <input id="duration" placeholder="时长毫秒" value="300">
                  <button onclick="swipe()">滑动</button>
                </div>
              </div>

              <div class="card">
                <h2>输入文字</h2>
                <div class="row">
                  <input id="textValue" placeholder="要输入的文字">
                  <button onclick="setText()">写入文字</button>
                </div>
              </div>

              <div class="card">
                <h2>启动应用</h2>
                <div class="row">
                  <input id="packageName" placeholder="例如 com.android.settings">
                  <button onclick="launchPackage()">启动</button>
                </div>
              </div>

              <div class="card">
                <h2>当前控件树</h2>
                <div class="row">
                  <button onclick="refreshNodes()">刷新控件树</button>
                </div>
                <div id="nodeList"></div>
              </div>

              <div class="card">
                <h2>最近响应</h2>
                <pre id="logBox"></pre>
              </div>

              <script>
                let autoScreen = true;
                let autoScreenTimer = null;

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
                  const screenTip = document.getElementById('screenTip');
                  if (!data.screenSupported) {
                    screenTip.textContent = '当前安卓版本不支持辅助服务截图，实时画面不可用。';
                  } else if (!data.accessibilityConnected) {
                    screenTip.textContent = '请先启用辅助功能服务，然后才能获取实时画面。';
                  } else {
                    screenTip.textContent = '提示：点击图片会直接映射成手机点击坐标。';
                  }
                }

                async function refreshScreen() {
                  const img = document.getElementById('screenView');
                  img.src = `/screen.jpg?ts=${'$'}{Date.now()}`;
                }

                function startAutoScreen() {
                  if (autoScreenTimer) {
                    clearInterval(autoScreenTimer);
                  }
                  autoScreenTimer = setInterval(() => {
                    if (autoScreen) {
                      refreshScreen();
                    }
                  }, 900);
                }

                function toggleAutoScreen() {
                  autoScreen = !autoScreen;
                  document.getElementById('screenTip').textContent = autoScreen
                    ? '自动刷新已开启，点击图片会直接映射成手机点击坐标。'
                    : '自动刷新已关闭，你可以手动点“刷新画面”。';
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
                    meta.textContent = `范围=${'$'}{node.bounds} 可点击=${'$'}{node.clickable} 可输入=${'$'}{node.editable}`;
                    div.appendChild(meta);

                    const button = document.createElement('button');
                    button.textContent = '点击这个控件';
                    button.onclick = () => call(`/node/click?path=${'$'}{encodeURIComponent(node.path)}`);
                    div.appendChild(button);

                    container.appendChild(div);
                  });
                }

                async function refreshAll() {
                  await refreshStatus();
                  await refreshScreen();
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

                document.getElementById('screenView').addEventListener('click', function(event) {
                  const img = event.currentTarget;
                  if (!img.naturalWidth || !img.naturalHeight) {
                    return;
                  }
                  const rect = img.getBoundingClientRect();
                  const x = Math.round((event.clientX - rect.left) * img.naturalWidth / rect.width);
                  const y = Math.round((event.clientY - rect.top) * img.naturalHeight / rect.height);
                  document.getElementById('tapX').value = x;
                  document.getElementById('tapY').value = y;
                  call(`/gesture/tap?x=${'$'}{encodeURIComponent(x)}&y=${'$'}{encodeURIComponent(y)}`).then(() => {
                    setTimeout(refreshScreen, 180);
                  });
                });

                startAutoScreen();
                refreshAll();
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
