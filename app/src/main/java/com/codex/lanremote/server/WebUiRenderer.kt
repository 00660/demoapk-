package com.codex.lanremote.server

import android.content.Context
import com.codex.lanremote.control.ControlCenter
import com.codex.lanremote.stream.StreamMode

object WebUiRenderer {
    fun render(context: Context): String {
        return when (ControlCenter.currentMode(context)) {
            StreamMode.BROWSER_MJPEG -> renderBrowserPage()
            StreamMode.LOW_LATENCY_H264 -> renderLowLatencyPage(context)
        }
    }

    private fun renderBrowserPage(): String {
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
              <title>实时屏幕</title>
              <style>
                html, body {
                  margin: 0;
                  padding: 0;
                  width: 100%;
                  height: 100%;
                  background: #000;
                  overflow: hidden;
                  overscroll-behavior: none;
                }
                body {
                  display: flex;
                  align-items: center;
                  justify-content: center;
                }
                #stage {
                  position: fixed;
                  inset: 0;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  background:
                    radial-gradient(circle at top, rgba(59,130,246,0.28), transparent 30%),
                    linear-gradient(180deg, #020617 0%, #000 100%);
                  width: 100dvw;
                  height: 100dvh;
                  overflow: hidden;
                }
                #phone {
                  position: relative;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  border-radius: 42px;
                  background:
                    linear-gradient(145deg, #0f172a 0%, #111827 35%, #020617 100%);
                  box-shadow:
                    0 20px 50px rgba(0, 0, 0, 0.58),
                    inset 0 0 0 2px rgba(255, 255, 255, 0.06),
                    inset 0 0 0 10px rgba(255, 255, 255, 0.03);
                }
                #speaker {
                  position: absolute;
                  top: 16px;
                  left: 50%;
                  transform: translateX(-50%);
                  width: 92px;
                  height: 10px;
                  border-radius: 999px;
                  background: rgba(0, 0, 0, 0.55);
                  box-shadow: inset 0 1px 2px rgba(255,255,255,0.08);
                }
                #camera {
                  position: absolute;
                  top: 16px;
                  right: 26px;
                  width: 10px;
                  height: 10px;
                  border-radius: 50%;
                  background: #0b1220;
                  box-shadow: inset 0 0 0 2px rgba(255,255,255,0.06);
                }
                #display {
                  position: relative;
                  overflow: hidden;
                  border-radius: 26px;
                  background: #000;
                  box-shadow: inset 0 0 0 1px rgba(255,255,255,0.05);
                }
                #screen {
                  width: 100%;
                  height: 100%;
                  background: #000;
                  user-select: none;
                  -webkit-user-drag: none;
                  touch-action: none;
                  object-fit: fill;
                }
                #homebar {
                  position: absolute;
                  bottom: 12px;
                  left: 50%;
                  transform: translateX(-50%);
                  width: 110px;
                  height: 5px;
                  border-radius: 999px;
                  background: rgba(255,255,255,0.22);
                }
                #tip {
                  position: fixed;
                  left: 10px;
                  right: 10px;
                  bottom: 10px;
                  padding: 10px 12px;
                  border-radius: 10px;
                  background: rgba(0, 0, 0, 0.55);
                  color: #fff;
                  font: 14px/1.5 "Microsoft YaHei", "PingFang SC", sans-serif;
                  text-align: center;
                }
              </style>
            </head>
            <body>
              <div id="stage">
                <div id="phone">
                  <div id="speaker"></div>
                  <div id="camera"></div>
                  <div id="display">
                    <img id="screen" src="/stream.mjpeg" alt="实时屏幕">
                  </div>
                  <div id="homebar"></div>
                </div>
              </div>
              <div id="tip">正在连接实时屏幕...</div>
              <script>
                const stage = document.getElementById('stage');
                const phone = document.getElementById('phone');
                const display = document.getElementById('display');
                const screen = document.getElementById('screen');
                const tip = document.getElementById('tip');
                let lastProjectionRequestAt = 0;
                let gestureStart = null;

                async function getStatus() {
                  const response = await fetch('/status');
                  return await response.json();
                }

                async function requestProjectionIfNeeded(status) {
                  if (status.projectionActive || status.projectionAwaitingApproval) {
                    return;
                  }
                  const now = Date.now();
                  if (now - lastProjectionRequestAt < 3000) {
                    return;
                  }
                  lastProjectionRequestAt = now;
                  try {
                    await fetch('/projection/request', { method: 'POST' });
                    tip.textContent = '正在请求系统录屏授权...';
                  } catch (e) {
                    tip.textContent = '录屏授权请求失败';
                  }
                }

                async function sendTap(x, y) {
                  await fetch('/gesture/tap?x=' + encodeURIComponent(x) + '&y=' + encodeURIComponent(y), { method: 'POST' });
                }

                async function sendSwipe(x1, y1, x2, y2) {
                  await fetch(
                    '/gesture/swipe?x1=' + encodeURIComponent(x1) +
                    '&y1=' + encodeURIComponent(y1) +
                    '&x2=' + encodeURIComponent(x2) +
                    '&y2=' + encodeURIComponent(y2) +
                    '&duration=220',
                    { method: 'POST' }
                  );
                }

                function mapPoint(clientX, clientY) {
                  if (!screen.naturalWidth || !screen.naturalHeight) {
                    return null;
                  }
                  const rect = display.getBoundingClientRect();
                  const x = Math.round((clientX - rect.left) * screen.naturalWidth / rect.width);
                  const y = Math.round((clientY - rect.top) * screen.naturalHeight / rect.height);
                  return { x: x, y: y };
                }

                function fitScreen() {
                  if (!screen.naturalWidth || !screen.naturalHeight) {
                    return;
                  }
                  const viewport = window.visualViewport || { width: window.innerWidth, height: window.innerHeight };
                  const viewportWidth = Math.max(1, Math.round(viewport.width));
                  const viewportHeight = Math.max(1, Math.round(viewport.height));
                  stage.style.width = viewportWidth + 'px';
                  stage.style.height = viewportHeight + 'px';

                  const frameSide = 26;
                  const frameTop = 34;
                  const frameBottom = 28;
                  const outerMargin = 14;
                  const sourceRatio = screen.naturalWidth / screen.naturalHeight;

                  const maxDisplayWidth = Math.max(1, viewportWidth - outerMargin * 2 - frameSide * 2);
                  const maxDisplayHeight = Math.max(1, viewportHeight - outerMargin * 2 - frameTop - frameBottom);

                  let displayWidth = maxDisplayWidth;
                  let displayHeight = Math.round(displayWidth / sourceRatio);
                  if (displayHeight > maxDisplayHeight) {
                    displayHeight = maxDisplayHeight;
                    displayWidth = Math.round(displayHeight * sourceRatio);
                  }

                  phone.style.width = (displayWidth + frameSide * 2) + 'px';
                  phone.style.height = (displayHeight + frameTop + frameBottom) + 'px';
                  phone.style.paddingLeft = frameSide + 'px';
                  phone.style.paddingRight = frameSide + 'px';
                  phone.style.paddingTop = frameTop + 'px';
                  phone.style.paddingBottom = frameBottom + 'px';

                  display.style.width = displayWidth + 'px';
                  display.style.height = displayHeight + 'px';
                  screen.style.width = displayWidth + 'px';
                  screen.style.height = displayHeight + 'px';
                }

                async function loop() {
                  try {
                    const status = await getStatus();
                    if (status.projectionActive) {
                      tip.style.display = 'none';
                    } else if (status.projectionAwaitingApproval) {
                      tip.style.display = 'block';
                      tip.textContent = '等待系统录屏授权通过...';
                    } else {
                      tip.style.display = 'block';
                      tip.textContent = '正在发起录屏授权...';
                    }
                    await requestProjectionIfNeeded(status);
                  } catch (e) {
                    tip.style.display = 'block';
                    tip.textContent = '实时屏幕连接失败';
                  }
                }

                display.addEventListener('pointerdown', function (event) {
                  gestureStart = mapPoint(event.clientX, event.clientY);
                });

                display.addEventListener('pointerup', async function (event) {
                  if (!gestureStart) {
                    return;
                  }
                  const endPoint = mapPoint(event.clientX, event.clientY);
                  if (!endPoint) {
                    gestureStart = null;
                    return;
                  }

                  const dx = Math.abs(endPoint.x - gestureStart.x);
                  const dy = Math.abs(endPoint.y - gestureStart.y);
                  if (dx < 16 && dy < 16) {
                    await sendTap(endPoint.x, endPoint.y);
                  } else {
                    await sendSwipe(gestureStart.x, gestureStart.y, endPoint.x, endPoint.y);
                  }
                  gestureStart = null;
                  setTimeout(function () { screen.src = '/stream.mjpeg?ts=' + Date.now(); }, 80);
                });

                screen.addEventListener('load', fitScreen);
                window.addEventListener('resize', fitScreen);
                if (window.visualViewport) {
                  window.visualViewport.addEventListener('resize', fitScreen);
                  window.visualViewport.addEventListener('scroll', fitScreen);
                }
                setInterval(loop, 350);
                loop();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderLowLatencyPage(context: Context): String {
        val status = ControlCenter.status(context)
        val host = status.optString("lowLatencyTcpHost")
        val port = status.optInt("lowLatencyTcpPort")
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>低延迟模式</title>
              <style>
                body {
                  margin: 0;
                  padding: 24px;
                  background: #020617;
                  color: #f8fafc;
                  font: 16px/1.7 "Microsoft YaHei", "PingFang SC", sans-serif;
                }
                .card {
                  max-width: 760px;
                  margin: 0 auto;
                  background: #111827;
                  border: 1px solid #1f2937;
                  border-radius: 16px;
                  padding: 20px;
                }
                code, pre {
                  background: #000;
                  border-radius: 12px;
                  padding: 12px;
                  display: block;
                  overflow: auto;
                }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>低延迟模式</h1>
                <p>当前不是浏览器实时画面模式，而是 H.264/TCP 模式。</p>
                <p>连接地址：</p>
                <code>tcp://$host:$port</code>
                <p>示例：</p>
                <pre>ffplay -fflags nobuffer -flags low_delay -framedrop -strict experimental -f h264 tcp://$host:$port</pre>
                <p>如果你要回到浏览器看屏幕，请在 App 里切回“浏览器模式”。</p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }
}
