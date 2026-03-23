# Docker 对接层

这是给手机端远控 APK 配套的 Docker 桥接层。

它做两件事：

- 对接手机的 HTTP 控制接口
- 同时提供两种可切换的画面模式

模式说明：

- `浏览器流`
  - 直接代理手机的 `MJPEG` 流
  - 浏览器里直接可看
- `低延迟流`
  - 对接手机的 `H.264/TCP`
  - Docker 内部用 `ffmpeg` 转成 `JSMpeg` 可播的 `mpegts/websocket`
  - 延迟会比单纯图片轮询低很多

## 使用

先修改 [docker-compose.yml](/C:/Users/16547/Desktop/v2/android-lan-remote/docker-bridge/docker-compose.yml) 里的 `PHONE_HOST`：

```yaml
PHONE_HOST: 192.168.2.224
```

启动：

```bash
docker compose up -d --build
```

打开：

```text
http://你的电脑IP:8090
```

## 前提

手机端 APK 需要：

- 辅助功能已启用
- 录屏授权已通过
- 网页服务能访问

低延迟模式额外需要：

- 手机端切到 `low_latency_h264`
- 手机端本地 TCP 端口 `27183` 正在输出 H.264
