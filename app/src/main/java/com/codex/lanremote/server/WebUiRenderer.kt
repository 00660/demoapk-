package com.codex.lanremote.server

import android.content.Context

object WebUiRenderer {
    fun render(context: Context): String {
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
                }
                #screen {
                  width: auto;
                  height: auto;
                  max-width: 100vw;
                  max-height: 100vh;
                  background: #000;
                  user-select: none;
                  -webkit-user-drag: none;
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
                <img id="screen" alt="实时屏幕">
              </div>
              <div id="tip">正在连接实时屏幕...</div>
              <script>
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

                async function refreshScreen() {
                  screen.src = '/screen.jpg?ts=' + Date.now();
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
                  const rect = screen.getBoundingClientRect();
                  const x = Math.round((clientX - rect.left) * screen.naturalWidth / rect.width);
                  const y = Math.round((clientY - rect.top) * screen.naturalHeight / rect.height);
                  return { x: x, y: y };
                }

                function fitScreen() {
                  if (!screen.naturalWidth || !screen.naturalHeight) {
                    return;
                  }
                  const viewportRatio = window.innerWidth / window.innerHeight;
                  const imageRatio = screen.naturalWidth / screen.naturalHeight;
                  if (imageRatio > viewportRatio) {
                    screen.style.width = '100vw';
                    screen.style.height = 'auto';
                  } else {
                    screen.style.width = 'auto';
                    screen.style.height = '100vh';
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
                    await refreshScreen();
                  } catch (e) {
                    tip.style.display = 'block';
                    tip.textContent = '实时屏幕连接失败';
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

                screen.addEventListener('load', fitScreen);
                window.addEventListener('resize', fitScreen);
                setInterval(loop, 350);
                loop();
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
