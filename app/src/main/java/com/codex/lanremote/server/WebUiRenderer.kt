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
                let startedProjectionRequest = false;

                async function getStatus() {
                  const response = await fetch('/status');
                  return await response.json();
                }

                async function requestProjectionIfNeeded(status) {
                  if (startedProjectionRequest) {
                    return;
                  }
                  if (status.projectionActive || status.projectionAwaitingApproval) {
                    return;
                  }
                  startedProjectionRequest = true;
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
