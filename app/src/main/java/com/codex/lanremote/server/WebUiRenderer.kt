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
                  background: #000;
                  width: 100dvw;
                  height: 100dvh;
                  overflow: hidden;
                }
                #screen {
                  display: block;
                  background: #000;
                  touch-action: none;
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
                <canvas id="screen"></canvas>
              </div>
              <div id="tip">正在连接实时屏幕...</div>
              <script>
                const stage = document.getElementById('stage');
                const screen = document.getElementById('screen');
                const ctx = screen.getContext('2d', { alpha: false, desynchronized: true });
                const tip = document.getElementById('tip');
                let lastProjectionRequestAt = 0;
                let gestureStart = null;
                let sourceWidth = 0;
                let sourceHeight = 0;
                let drawRect = null;
                let reading = false;

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
                  if (!sourceWidth || !sourceHeight || !drawRect) {
                    return null;
                  }
                  const x = Math.round((clientX - drawRect.left) * sourceWidth / drawRect.width);
                  const y = Math.round((clientY - drawRect.top) * sourceHeight / drawRect.height);
                  return { x: x, y: y };
                }

                function fitCanvas() {
                  if (!sourceWidth || !sourceHeight) {
                    return;
                  }
                  const viewport = window.visualViewport || { width: window.innerWidth, height: window.innerHeight };
                  const viewportWidth = Math.max(1, Math.round(viewport.width));
                  const viewportHeight = Math.max(1, Math.round(viewport.height));
                  stage.style.width = viewportWidth + 'px';
                  stage.style.height = viewportHeight + 'px';
                  screen.width = viewportWidth;
                  screen.height = viewportHeight;
                  screen.style.width = viewportWidth + 'px';
                  screen.style.height = viewportHeight + 'px';

                  const sourceRatio = sourceWidth / sourceHeight;
                  const viewportRatio = viewportWidth / viewportHeight;
                  let drawWidth = viewportWidth;
                  let drawHeight = Math.round(drawWidth / sourceRatio);
                  if (drawHeight > viewportHeight) {
                    drawHeight = viewportHeight;
                    drawWidth = Math.round(drawHeight * sourceRatio);
                  }
                  const left = Math.floor((viewportWidth - drawWidth) / 2);
                  const top = Math.floor((viewportHeight - drawHeight) / 2);
                  drawRect = { left: left, top: top, width: drawWidth, height: drawHeight };
                }

                async function drawFrame(bytes) {
                  const blob = new Blob([bytes], { type: 'image/jpeg' });
                  const bitmap = await createImageBitmap(blob);
                  sourceWidth = bitmap.width;
                  sourceHeight = bitmap.height;
                  fitCanvas();
                  if (drawRect) {
                    ctx.fillStyle = '#000';
                    ctx.fillRect(0, 0, screen.width, screen.height);
                    ctx.drawImage(bitmap, drawRect.left, drawRect.top, drawRect.width, drawRect.height);
                  }
                  bitmap.close();
                }

                async function openFrameStream() {
                  if (reading) {
                    return;
                  }
                  reading = true;
                  try {
                    const response = await fetch('/stream.bin?ts=' + Date.now(), { cache: 'no-store' });
                    const reader = response.body.getReader();
                    let buffer = new Uint8Array(0);
                    while (true) {
                      const result = await reader.read();
                      if (result.done) {
                        break;
                      }
                    const incoming = result.value;
                    const merged = new Uint8Array(buffer.length + incoming.length);
                    merged.set(buffer, 0);
                    merged.set(incoming, buffer.length);
                    buffer = merged;

                    let latestFrameBytes = null;
                    while (buffer.length >= 4) {
                      const view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
                      const frameLength = view.getInt32(0);
                      if (buffer.length < 4 + frameLength) {
                        break;
                      }
                      latestFrameBytes = buffer.slice(4, 4 + frameLength);
                      buffer = buffer.slice(4 + frameLength);
                    }
                    if (latestFrameBytes) {
                      await drawFrame(latestFrameBytes);
                    }
                  }
                } catch (e) {
                    tip.style.display = 'block';
                    tip.textContent = '实时画面连接失败';
                  } finally {
                    reading = false;
                    setTimeout(openFrameStream, 300);
                  }
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
                    tip.textContent = '状态同步失败';
                  }
                }

                screen.addEventListener('pointerdown', function (event) {
                  gestureStart = mapPoint(event.clientX, event.clientY);
                });

                screen.addEventListener('pointerup', async function (event) {
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
                });

                window.addEventListener('resize', fitCanvas);
                if (window.visualViewport) {
                  window.visualViewport.addEventListener('resize', fitCanvas);
                  window.visualViewport.addEventListener('scroll', fitCanvas);
                }
                openFrameStream();
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
