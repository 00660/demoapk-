import asyncio
import os
from dataclasses import dataclass
from typing import Optional

import httpx
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, JSONResponse, StreamingResponse


@dataclass
class Settings:
    phone_host: str = os.getenv("PHONE_HOST", "192.168.2.224")
    phone_http_port: int = int(os.getenv("PHONE_HTTP_PORT", "8080"))
    phone_h264_port: int = int(os.getenv("PHONE_H264_PORT", "27183"))
    bridge_port: int = int(os.getenv("BRIDGE_PORT", "8090"))

    @property
    def phone_http_base(self) -> str:
        return f"http://{self.phone_host}:{self.phone_http_port}"

    @property
    def phone_h264_tcp_url(self) -> str:
        return f"tcp://{self.phone_host}:{self.phone_h264_port}"


SETTINGS = Settings()
app = FastAPI(title="LAN Remote Docker Bridge")


class LowLatencyBridge:
    def __init__(self) -> None:
        self.clients: set[WebSocket] = set()
        self.task: Optional[asyncio.Task] = None
        self.lock = asyncio.Lock()

    async def connect(self, websocket: WebSocket) -> None:
        await websocket.accept()
        self.clients.add(websocket)
        async with self.lock:
            if self.task is None or self.task.done():
                self.task = asyncio.create_task(self._run())

    async def disconnect(self, websocket: WebSocket) -> None:
        self.clients.discard(websocket)

    async def _broadcast(self, chunk: bytes) -> None:
        dead: list[WebSocket] = []
        for client in list(self.clients):
            try:
                await client.send_bytes(chunk)
            except Exception:
                dead.append(client)
        for client in dead:
            self.clients.discard(client)

    async def _run(self) -> None:
        while self.clients:
            process = await asyncio.create_subprocess_exec(
                "ffmpeg",
                "-loglevel",
                "error",
                "-fflags",
                "nobuffer",
                "-flags",
                "low_delay",
                "-probesize",
                "32",
                "-analyzeduration",
                "0",
                "-f",
                "h264",
                "-i",
                SETTINGS.phone_h264_tcp_url,
                "-an",
                "-c:v",
                "mpeg1video",
                "-g",
                "1",
                "-bf",
                "0",
                "-r",
                "30",
                "-b:v",
                "2000k",
                "-f",
                "mpegts",
                "-",
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            try:
                while self.clients:
                    chunk = await process.stdout.read(8192)
                    if not chunk:
                        break
                    await self._broadcast(chunk)
            finally:
                if process.returncode is None:
                    process.kill()
                    await process.wait()
            if self.clients:
                await asyncio.sleep(1)


LOW_LATENCY_BRIDGE = LowLatencyBridge()


async def phone_request(method: str, path: str, **kwargs):
    async with httpx.AsyncClient(timeout=15.0) as client:
        response = await client.request(method, SETTINGS.phone_http_base + path, **kwargs)
        response.raise_for_status()
        return response


def page_html() -> str:
    return f"""
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
  <title>Docker 对接控制</title>
  <style>
    html, body {{
      margin: 0;
      padding: 0;
      width: 100%;
      height: 100%;
      background: #000;
      overflow: hidden;
      overscroll-behavior: none;
    }}
    body {{
      font-family: "Microsoft YaHei", "PingFang SC", sans-serif;
    }}
    #stage {{
      position: fixed;
      inset: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      background: #000;
      overflow: hidden;
    }}
    #mjpeg, #llcanvas {{
      background: #000;
      max-width: 100vw;
      max-height: 100vh;
      user-select: none;
      touch-action: none;
      display: none;
    }}
    #hud {{
      position: fixed;
      top: 10px;
      left: 10px;
      right: 10px;
      display: flex;
      justify-content: space-between;
      gap: 8px;
      pointer-events: none;
    }}
    #modes, #status {{
      display: flex;
      gap: 8px;
      align-items: center;
      pointer-events: auto;
    }}
    button {{
      border: 0;
      border-radius: 999px;
      padding: 10px 14px;
      background: rgba(17, 24, 39, 0.78);
      color: #fff;
      cursor: pointer;
    }}
    button.active {{
      background: #2563eb;
    }}
    #tip {{
      position: fixed;
      left: 10px;
      right: 10px;
      bottom: 10px;
      padding: 10px 12px;
      border-radius: 10px;
      background: rgba(0, 0, 0, 0.55);
      color: #fff;
      text-align: center;
    }}
  </style>
</head>
<body>
  <div id="stage">
    <img id="mjpeg" alt="浏览器流">
    <canvas id="llcanvas"></canvas>
  </div>

  <div id="hud">
    <div id="modes">
      <button id="modeBrowser" onclick="switchMode('browser_mjpeg')">浏览器流</button>
      <button id="modeLow" onclick="switchMode('low_latency_h264')">低延迟流</button>
    </div>
    <div id="status">
      <button onclick="requestProjection()">发起录屏授权</button>
      <button onclick="sendAction('back')">返回</button>
      <button onclick="sendAction('home')">主页</button>
    </div>
  </div>

  <div id="tip">正在连接...</div>

  <script src="https://cdn.jsdelivr.net/npm/jsmpeg@0.2.1/jsmpeg.min.js"></script>
  <script>
    const stage = document.getElementById('stage');
    const mjpeg = document.getElementById('mjpeg');
    const llcanvas = document.getElementById('llcanvas');
    const tip = document.getElementById('tip');
    const modeBrowser = document.getElementById('modeBrowser');
    const modeLow = document.getElementById('modeLow');
    let mode = 'browser_mjpeg';
    let player = null;
    let pointerStart = null;
    let lastProjectionRequestAt = 0;

    async function api(path, method = 'POST') {{
      const response = await fetch(path, {{ method }});
      return await response.json();
    }}

    async function fetchStatus() {{
      const response = await fetch('/api/status');
      return await response.json();
    }}

    async function requestProjection() {{
      await api('/api/projection/request');
    }}

    async function sendAction(name) {{
      await api('/api/action/' + encodeURIComponent(name));
    }}

    async function switchMode(nextMode) {{
      mode = nextMode;
      await api('/api/phone/mode/' + encodeURIComponent(nextMode));
      await api('/api/bridge/mode/' + encodeURIComponent(nextMode));
      renderMode();
    }}

    async function sendTap(x, y) {{
      await fetch('/api/tap?x=' + encodeURIComponent(x) + '&y=' + encodeURIComponent(y), {{ method: 'POST' }});
    }}

    async function sendSwipe(x1, y1, x2, y2) {{
      await fetch(
        '/api/swipe?x1=' + encodeURIComponent(x1) +
        '&y1=' + encodeURIComponent(y1) +
        '&x2=' + encodeURIComponent(x2) +
        '&y2=' + encodeURIComponent(y2) +
        '&duration=220',
        {{ method: 'POST' }}
      );
    }}

    function visualSize() {{
      const viewport = window.visualViewport || {{ width: window.innerWidth, height: window.innerHeight }};
      return {{
        width: Math.max(1, Math.round(viewport.width)),
        height: Math.max(1, Math.round(viewport.height))
      }};
    }}

    function fitMjpeg() {{
      if (!mjpeg.naturalWidth || !mjpeg.naturalHeight) {{
        return;
      }}
      const vp = visualSize();
      const viewportRatio = vp.width / vp.height;
      const imageRatio = mjpeg.naturalWidth / mjpeg.naturalHeight;
      mjpeg.style.maxWidth = vp.width + 'px';
      mjpeg.style.maxHeight = vp.height + 'px';
      if (imageRatio > viewportRatio) {{
        mjpeg.style.width = vp.width + 'px';
        mjpeg.style.height = 'auto';
      }} else {{
        mjpeg.style.width = 'auto';
        mjpeg.style.height = vp.height + 'px';
      }}
    }}

    function mapPoint(target, clientX, clientY) {{
      const rect = target.getBoundingClientRect();
      let sourceWidth = 0;
      let sourceHeight = 0;
      if (mode === 'browser_mjpeg') {{
        sourceWidth = mjpeg.naturalWidth;
        sourceHeight = mjpeg.naturalHeight;
      }} else {{
        sourceWidth = llcanvas.width;
        sourceHeight = llcanvas.height;
      }}
      if (!sourceWidth || !sourceHeight) {{
        return null;
      }}
      return {{
        x: Math.round((clientX - rect.left) * sourceWidth / rect.width),
        y: Math.round((clientY - rect.top) * sourceHeight / rect.height)
      }};
    }}

    function attachPointer(target) {{
      target.addEventListener('pointerdown', function(event) {{
        pointerStart = mapPoint(target, event.clientX, event.clientY);
      }});

      target.addEventListener('pointerup', async function(event) {{
        if (!pointerStart) {{
          return;
        }}
        const endPoint = mapPoint(target, event.clientX, event.clientY);
        if (!endPoint) {{
          pointerStart = null;
          return;
        }}
        const dx = Math.abs(endPoint.x - pointerStart.x);
        const dy = Math.abs(endPoint.y - pointerStart.y);
        if (dx < 16 && dy < 16) {{
          await sendTap(endPoint.x, endPoint.y);
        }} else {{
          await sendSwipe(pointerStart.x, pointerStart.y, endPoint.x, endPoint.y);
        }}
        pointerStart = null;
      }});
    }}

    function startBrowserMode() {{
      if (player) {{
        player.destroy();
        player = null;
      }}
      llcanvas.style.display = 'none';
      mjpeg.style.display = 'block';
      mjpeg.src = '/proxy/stream.mjpeg?ts=' + Date.now();
      fitMjpeg();
    }}

    function startLowLatencyMode() {{
      mjpeg.style.display = 'none';
      llcanvas.style.display = 'block';
      if (player) {{
        player.destroy();
      }}
      const protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
      player = new JSMpeg.Player(protocol + location.host + '/ws/lowlatency', {{
        canvas: llcanvas,
        autoplay: true,
        audio: false,
        videoBufferSize: 1024 * 1024,
        preserveDrawingBuffer: true
      }});
    }}

    function renderMode() {{
      modeBrowser.classList.toggle('active', mode === 'browser_mjpeg');
      modeLow.classList.toggle('active', mode === 'low_latency_h264');
      if (mode === 'browser_mjpeg') {{
        startBrowserMode();
      }} else {{
        startLowLatencyMode();
      }}
    }}

    async function refreshState() {{
      try {{
        const status = await fetchStatus();
        mode = status.streamMode || mode;
        renderMode();
        if (status.projectionActive) {{
          tip.textContent = '';
        }} else if (status.projectionAwaitingApproval) {{
          tip.textContent = '等待系统录屏授权通过...';
        }} else {{
          const now = Date.now();
          if (now - lastProjectionRequestAt > 3000) {{
            lastProjectionRequestAt = now;
            await requestProjection();
          }}
          tip.textContent = '正在发起录屏授权...';
        }}
      }} catch (e) {{
        tip.textContent = 'Docker 对接层连接失败';
      }}
    }}

    mjpeg.addEventListener('load', fitMjpeg);
    window.addEventListener('resize', fitMjpeg);
    if (window.visualViewport) {{
      window.visualViewport.addEventListener('resize', fitMjpeg);
      window.visualViewport.addEventListener('scroll', fitMjpeg);
    }}

    attachPointer(mjpeg);
    attachPointer(llcanvas);
    setInterval(refreshState, 500);
    refreshState();
  </script>
</body>
</html>
"""


@app.get("/")
async def root() -> HTMLResponse:
    return HTMLResponse(page_html())


@app.get("/api/status")
async def api_status() -> JSONResponse:
    response = await phone_request("GET", "/status")
    data = response.json()
    data["bridgeBrowserMjpeg"] = "/proxy/stream.mjpeg"
    data["bridgeLowLatencyWs"] = "/ws/lowlatency"
    return JSONResponse(data)


@app.post("/api/projection/request")
async def api_projection_request() -> JSONResponse:
    response = await phone_request("POST", "/projection/request")
    return JSONResponse(response.json())


@app.post("/api/phone/mode/{mode}")
async def api_phone_mode(mode: str) -> JSONResponse:
    response = await phone_request("POST", "/mode/set", params={"value": mode})
    return JSONResponse(response.json())


@app.post("/api/bridge/mode/{mode}")
async def api_bridge_mode(mode: str) -> JSONResponse:
    return JSONResponse({"success": True, "mode": mode})


@app.post("/api/action/{name}")
async def api_action(name: str) -> JSONResponse:
    response = await phone_request("POST", f"/action", params={"name": name})
    return JSONResponse(response.json())


@app.post("/api/tap")
async def api_tap(x: int, y: int) -> JSONResponse:
    response = await phone_request("POST", "/gesture/tap", params={"x": x, "y": y})
    return JSONResponse(response.json())


@app.post("/api/swipe")
async def api_swipe(x1: int, y1: int, x2: int, y2: int, duration: int = 220) -> JSONResponse:
    response = await phone_request(
        "POST",
        "/gesture/swipe",
        params={"x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration": duration},
    )
    return JSONResponse(response.json())


@app.get("/proxy/stream.mjpeg")
async def proxy_stream_mjpeg():
    async def iterator():
        async with httpx.AsyncClient(timeout=None) as client:
            async with client.stream("GET", SETTINGS.phone_http_base + "/stream.mjpeg") as response:
                async for chunk in response.aiter_bytes():
                    yield chunk

    return StreamingResponse(
        iterator(),
        media_type="multipart/x-mixed-replace; boundary=frame",
        headers={"Cache-Control": "no-store"},
    )


@app.websocket("/ws/lowlatency")
async def websocket_lowlatency(websocket: WebSocket):
    await LOW_LATENCY_BRIDGE.connect(websocket)
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        await LOW_LATENCY_BRIDGE.disconnect(websocket)
    except Exception:
        await LOW_LATENCY_BRIDGE.disconnect(websocket)
