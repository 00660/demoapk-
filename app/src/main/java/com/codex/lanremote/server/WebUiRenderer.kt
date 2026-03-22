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
                body {
                  font-family: "Microsoft YaHei", "PingFang SC", sans-serif;
                  margin: 0;
                  background: #0f172a;
                  color: #f8fafc;
                }
                .page {
                  max-width: 980px;
                  margin: 0 auto;
                  padding: 16px;
                }
                .hero {
                  margin-bottom: 16px;
                }
                h1, h2, h3 {
                  margin: 0 0 10px 0;
                }
                .muted {
                  color: #93c5fd;
                }
                .card {
                  background: #111827;
                  border: 1px solid #1f2937;
                  border-radius: 16px;
                  padding: 14px;
                  margin-bottom: 16px;
                }
                .screen-shell {
                  background: #020617;
                  border-radius: 18px;
                  padding: 10px;
                }
                .screen-view {
                  width: 100%;
                  display: block;
                  border-radius: 12px;
                  border: 1px solid #334155;
                  background: #000;
                  user-select: none;
                  touch-action: none;
                  cursor: crosshair;
                }
                .toolbar, .row {
                  display: flex;
                  gap: 8px;
                  flex-wrap: wrap;
                  margin-top: 10px;
                }
                button {
                  border: 0;
                  border-radius: 10px;
                  padding: 10px 14px;
                  cursor: pointer;
                  background: #2563eb;
                  color: #fff;
                  font-size: 14px;
                }
                button.secondary {
                  background: #334155;
                }
                input {
                  border-radius: 10px;
                  border: 1px solid #334155;
                  background: #0b1220;
                  color: #fff;
                  padding: 10px;
                  min-width: 120px;
                  font-size: 14px;
                }
                pre {
                  white-space: pre-wrap;
                  word-break: break-word;
                  background: #020617;
                  border-radius: 12px;
                  padding: 12px;
                  overflow: auto;
                }
                details {
                  margin-top: 12px;
                }
                summary {
                  cursor: pointer;
                  color: #cbd5e1;
                }
                .node {
                  border-top: 1px solid #334155;
                  padding-top: 8px;
                  margin-top: 8px;
                }
                .tip {
                  margin-top: 10px;
                  color: #cbd5e1;
                  line-height: 1.6;
                }
              </style>
            </head>
            <body>
              <div class="page">
                <div class="hero">
                  <h1>局域网远程控制</h1>
                  <div class="muted">访问地址：$baseUrl</div>
                </div>

                <div class="card">
                  <h2>实时屏幕</h2>
                  <div class="screen-shell">
                    <img id="screenView" class="screen-view" alt="实时屏幕画面">
                  </div>
                  <div class="toolbar">
                    <button onclick="requestProjection()">发起录屏授权</button>
                    <button onclick="refreshScreen()">刷新画面</button>
                    <button onclick="toggleAutoRefresh()" class="secondary">切换自动刷新</button>
                    <button onclick="action('back')" class="secondary">返回</button>
                    <button onclick="action('home')" class="secondary">主页</button>
                    <button onclick="action('recents')" class="secondary">最近任务</button>
                    <button onclick="action('notifications')" class="secondary">通知栏</button>
                  </div>
                  <div id="screenTip" class="tip">
                    安卓 8.1 需要先通过一次系统录屏授权，之后这里才会持续显示屏幕。
                    直接点击画面就是点屏，按住拖动就是滑动。
                  </div>
                </div>

                <div class="card">
                  <h3>状态</h3>
                  <pre id="statusBox">正在加载状态...</pre>
                </div>

                <details class="card">
                  <summary>展开高级操作</summary>
                  <div class="row">
                    <input id="textValue" placeholder="输入文字">
                    <button onclick="setText()">写入文字</button>
                  </div>
                  <div class="row">
                    <input id="packageName" placeholder="应用包名，例如 com.android.settings">
                    <button onclick="launchPackage()">启动应用</button>
                  </div>
                  <div class="row">
                    <input id="tapX" placeholder="点击 X" value="300">
                    <input id="tapY" placeholder="点击 Y" value="600">
                    <button onclick="tap()">按坐标点击</button>
                  </div>
                  <div class="row">
                    <input id="x1" placeholder="起点 X" value="300">
                    <input id="y1" placeholder="起点 Y" value="1000">
                    <input id="x2" placeholder="终点 X" value="300">
                    <input id="y2" placeholder="终点 Y" value="300">
                    <input id="duration" placeholder="时长毫秒" value="300">
                    <button onclick="swipe()">按坐标滑动</button>
                  </div>
                  <div class="row">
                    <button onclick="refreshNodes()" class="secondary">刷新控件树</button>
                  </div>
                  <div id="nodeList"></div>
                </details>

                <div class="card">
                  <h3>最近响应</h3>
                  <pre id="logBox">暂无响应</pre>
                </div>
              </div>

              <script>
                let 自动刷新 = true;
                let 刷新定时器 = null;
                let 起点 = null;

                async function 调接口(path, method = 'POST') {
                  const response = await fetch(path, { method: method });
                  const data = await response.json();
                  document.getElementById('logBox').textContent = JSON.stringify(data, null, 2);
                  return data;
                }

                async function 刷新状态() {
                  const response = await fetch('/status');
                  const data = await response.json();
                  document.getElementById('statusBox').textContent = JSON.stringify(data, null, 2);

                  const 提示 = document.getElementById('screenTip');
                  if (data.projectionActive) {
                    提示.textContent = '录屏已开启。直接点击画面就是点屏，按住拖动就是滑动。';
                  } else if (data.projectionAwaitingApproval) {
                    提示.textContent = '录屏授权请求已发起，正在等待系统弹窗通过。';
                  } else {
                    提示.textContent = '安卓 8.1 需要先通过一次系统录屏授权，之后这里才会持续显示屏幕。';
                  }
                }

                async function requestProjection() {
                  const data = await 调接口('/projection/request');
                  setTimeout(刷新状态, 600);
                  return data;
                }

                async function refreshScreen() {
                  const img = document.getElementById('screenView');
                  img.src = '/screen.jpg?ts=' + Date.now();
                }

                function startAutoRefresh() {
                  if (刷新定时器) {
                    clearInterval(刷新定时器);
                  }
                  刷新定时器 = setInterval(function () {
                    if (自动刷新) {
                      refreshScreen();
                    }
                  }, 350);
                }

                function toggleAutoRefresh() {
                  自动刷新 = !自动刷新;
                  document.getElementById('screenTip').textContent = 自动刷新
                    ? '自动刷新已开启。'
                    : '自动刷新已关闭，可以手动点击“刷新画面”。';
                }

                function 映射坐标(img, clientX, clientY) {
                  const rect = img.getBoundingClientRect();
                  const x = Math.round((clientX - rect.left) * img.naturalWidth / rect.width);
                  const y = Math.round((clientY - rect.top) * img.naturalHeight / rect.height);
                  return { x: x, y: y };
                }

                function action(name) {
                  return 调接口('/action?name=' + encodeURIComponent(name));
                }

                function tap() {
                  const x = document.getElementById('tapX').value;
                  const y = document.getElementById('tapY').value;
                  return 调接口('/gesture/tap?x=' + encodeURIComponent(x) + '&y=' + encodeURIComponent(y));
                }

                function swipe() {
                  const x1 = document.getElementById('x1').value;
                  const y1 = document.getElementById('y1').value;
                  const x2 = document.getElementById('x2').value;
                  const y2 = document.getElementById('y2').value;
                  const duration = document.getElementById('duration').value;
                  return 调接口(
                    '/gesture/swipe?x1=' + encodeURIComponent(x1) +
                    '&y1=' + encodeURIComponent(y1) +
                    '&x2=' + encodeURIComponent(x2) +
                    '&y2=' + encodeURIComponent(y2) +
                    '&duration=' + encodeURIComponent(duration)
                  );
                }

                function setText() {
                  const value = document.getElementById('textValue').value;
                  return 调接口('/text?value=' + encodeURIComponent(value));
                }

                function launchPackage() {
                  const packageName = document.getElementById('packageName').value;
                  return 调接口('/launch?package=' + encodeURIComponent(packageName));
                }

                async function refreshNodes() {
                  const response = await fetch('/nodes');
                  const data = await response.json();
                  const container = document.getElementById('nodeList');
                  container.innerHTML = '';
                  if (!data.nodes) {
                    container.textContent = data.message || '没有控件信息';
                    return;
                  }

                  data.nodes.forEach(function (node) {
                    const div = document.createElement('div');
                    div.className = 'node';

                    const title = document.createElement('div');
                    title.textContent = node.path + ' | ' + node.className + ' | ' + (node.text || node.contentDescription || '(无文字)');
                    div.appendChild(title);

                    const meta = document.createElement('div');
                    meta.textContent = '范围=' + node.bounds + ' 可点击=' + node.clickable + ' 可输入=' + node.editable;
                    div.appendChild(meta);

                    const button = document.createElement('button');
                    button.textContent = '点击这个控件';
                    button.onclick = function () {
                      调接口('/node/click?path=' + encodeURIComponent(node.path));
                    };
                    div.appendChild(button);

                    container.appendChild(div);
                  });
                }

                document.getElementById('screenView').addEventListener('pointerdown', function (event) {
                  const img = event.currentTarget;
                  if (!img.naturalWidth || !img.naturalHeight) {
                    return;
                  }
                  起点 = 映射坐标(img, event.clientX, event.clientY);
                });

                document.getElementById('screenView').addEventListener('pointerup', function (event) {
                  const img = event.currentTarget;
                  if (!img.naturalWidth || !img.naturalHeight || !起点) {
                    return;
                  }
                  const 终点 = 映射坐标(img, event.clientX, event.clientY);
                  const dx = Math.abs(终点.x - 起点.x);
                  const dy = Math.abs(终点.y - 起点.y);

                  document.getElementById('tapX').value = 终点.x;
                  document.getElementById('tapY').value = 终点.y;
                  document.getElementById('x1').value = 起点.x;
                  document.getElementById('y1').value = 起点.y;
                  document.getElementById('x2').value = 终点.x;
                  document.getElementById('y2').value = 终点.y;

                  if (dx < 16 && dy < 16) {
                    调接口('/gesture/tap?x=' + encodeURIComponent(终点.x) + '&y=' + encodeURIComponent(终点.y)).then(function () {
                      setTimeout(refreshScreen, 120);
                    });
                  } else {
                    调接口(
                      '/gesture/swipe?x1=' + encodeURIComponent(起点.x) +
                      '&y1=' + encodeURIComponent(起点.y) +
                      '&x2=' + encodeURIComponent(终点.x) +
                      '&y2=' + encodeURIComponent(终点.y) +
                      '&duration=220'
                    ).then(function () {
                      setTimeout(refreshScreen, 160);
                    });
                  }

                  起点 = null;
                });

                async function refreshAll() {
                  await 刷新状态();
                  await refreshScreen();
                }

                startAutoRefresh();
                refreshAll();
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
