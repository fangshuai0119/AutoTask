# AutoTask 远程轮询后端

独立小服务，供手机端 AutoTask（`feature/remote-poll-task`）轮询是否执行任务，并上报结果。

**不需要 docker-compose**，一个 Dockerfile 即可。

目录：`remote-poll-server/`

---

## 接口

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/tasks/pending` | 客户端轮询 | 无 |
| POST | `/tasks/report` | 客户端上报 | 无 |
| POST | `/tasks/enqueue` | 投递待执行任务 | 可选 X-Api-Key |
| GET | `/tasks` | 查看队列与上报 | 可选 X-Api-Key |
| DELETE | `/tasks/pending` | 清空队列 | 可选 X-Api-Key |
| GET | `/health` | 健康检查 | 无 |

客户端约定与 App 中 `RemotePollEventDispatcher` 一致。

---

## Docker 部署（推荐）

```bash
cd remote-poll-server

# 构建
docker build -t autotask-poll-server .

# 运行（不要用 docker-compose）
docker run -d \
  --name autotask-poll \
  -p 8080:8080 \
  -e API_KEY="请改成你的密钥" \
  --restart unless-stopped \
  autotask-poll-server

# 验证
curl http://127.0.0.1:8080/health
```

常用命令：

```bash
docker logs -f autotask-poll
docker restart autotask-poll
docker stop autotask-poll && docker rm autotask-poll
```

公网访问时请放行端口，并建议用 Nginx/Caddy 做 HTTPS。
手机 baseUrl 示例：`https://poll.example.com` 或局域网 `http://192.168.x.x:8080`。

> Android 9+ 默认限制明文 HTTP，正式环境请用 HTTPS。

---

## 本机直接运行（不用 Docker）

```bash
cd remote-poll-server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export API_KEY="请改成你的密钥"
python app.py
```

---

## 投递任务（让手机执行）

1. 手机上任务须为 **已启用的常驻任务**，记下其 **checksum**（`XTask.metadata.checksum`）。
2. 向服务器入队：

```bash
curl -X POST http://你的服务器:8080/tasks/enqueue \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: 请改成你的密钥" \
  -d '{"taskChecksum": 1234567890123456789, "payload": "可选", "force": false}'
```

3. 查看队列：

```bash
curl http://你的服务器:8080/tasks -H "X-Api-Key: 请改成你的密钥"
```

同一 checksum 被取走后默认 60 秒内不重复下发（`DEDUP_SECONDS`）。立即再跑可设 `"force": true`。

---

## 手机 App 配置

分支：`feature/remote-poll-task`

1. 打开 **远程轮询** 设置（设置/关于入口，若已加入 UI）。
2. 开启 **启用远程轮询**。
3. **服务器地址** 填 baseUrl（不要带 `/tasks/pending`）：
   - 局域网：`http://192.168.1.100:8080`
   - 公网：`https://poll.example.com`
4. **轮询间隔** 建议 ≥ 5 秒（默认约 15 秒）。
5. 保存；确保服务已启动、网络互通。

代码调试也可：

```kotlin
Preferences.remotePollEnabled = true
Preferences.remoteServerUrl = "http://192.168.1.100:8080"
Preferences.remotePollIntervalMs = 10_000L
```

模拟器访问电脑宿主机常用：`http://10.0.2.2:8080`。

---

## 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| API_KEY | 空 | 管理接口鉴权；空则不校验 |
| DEDUP_SECONDS | 60 | 同一 checksum 去重秒数 |
| PORT | 8080 | 仅本机 python 运行时有效 |

---

## 自测

```bash
docker run -d --name autotask-poll -p 8080:8080 -e API_KEY=test autotask-poll-server

curl -X POST http://127.0.0.1:8080/tasks/enqueue \
  -H "Content-Type: application/json" -H "X-Api-Key: test" \
  -d '{"taskChecksum": 123456789, "payload": "hello"}'

curl http://127.0.0.1:8080/tasks/pending
# {"shouldExecute":true,"taskChecksum":123456789,"payload":"hello"}

curl -X POST http://127.0.0.1:8080/tasks/report \
  -H "Content-Type: application/json" \
  -d '{"taskChecksum":123456789,"success":true,"message":null}'
```

数据存内存，容器重启会清空队列。需要持久化可自行改 Redis/SQLite。
