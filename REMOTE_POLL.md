# 远程轮询任务说明 (Remote Poll)

本分支增加了**客户端轮询服务器接口**来决定是否执行任务，并在执行完成后上报状态的功能。

## 功能概述

1. 客户端按设定间隔轮询你的服务器 `GET /tasks/pending`
2. 若返回需要执行，则根据 `taskChecksum` 触发对应的**常驻任务**
3. 任务结束后自动 `POST /tasks/report` 上报成功/失败状态

## 配置方式

通过 `Preferences`（SharedPreferences）配置：

| 键名 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `remote_poll_enabled` | Boolean | false | 是否启用远程轮询 |
| `remote_server_url` | String | null | 服务器 baseUrl，如 `https://your-server.com/api` |
| `remote_poll_interval_ms` | Long | 15000 | 轮询间隔（毫秒），建议 ≥ 5000 |

可在代码中设置，例如：

```kotlin
Preferences.remotePollEnabled = true
Preferences.remoteServerUrl = "https://your-server.com/api"
Preferences.remotePollIntervalMs = 10_000L
```

建议后续在设置页面增加 UI 入口。

## 服务端接口约定

### 1. 轮询待执行任务

```
GET {baseUrl}/tasks/pending
```

成功响应示例（HTTP 200）：

```json
{
  "shouldExecute": true,
  "taskChecksum": 1234567890123456789,
  "payload": "可选附加数据"
}
```

- `shouldExecute`: 是否需要执行
- `taskChecksum`: 本地任务的 checksum（Long），与 XTask.metadata.checksum 对应
- `payload`: 可选字符串，会放入事件 extra

若不需要执行，可返回：

```json
{ "shouldExecute": false }
```

### 2. 上报任务状态

```
POST {baseUrl}/tasks/report
Content-Type: application/json
```

Body 示例：

```json
{
  "taskChecksum": 1234567890123456789,
  "success": true,
  "message": null
}
```

失败时：

```json
{
  "taskChecksum": 1234567890123456789,
  "success": false,
  "message": "failure 或错误信息"
}
```

## 如何获取任务 checksum

在应用内创建/导入任务后，可在任务详情或调试日志中查看 `XTask.checksum`。  
也可在代码中通过 `task.metadata.checksum` 获取。

## 代码改动位置

- 新增：`app/.../task/event/RemotePollEventDispatcher.kt`
- 修改：`AutomatorService.kt`（注册 Dispatcher）
- 修改：`ResidentTaskScheduler.kt`（处理远程事件 + 上报状态）
- 修改：`Preferences.kt`（新增配置项）

## 注意事项

1. 轮询间隔不要过短，以免耗电和增加服务器压力。
2. 任务必须是**已启用的常驻任务**，且 checksum 匹配才会被触发。
3. 如需鉴权，可在 `RemotePollEventDispatcher` 中自行添加 Header（参考现有 `api/Client.kt`）。
4. 当前实现主要针对常驻任务；一次性任务仍可通过原有方式调度。

## 分支

当前改动在分支：`feature/remote-poll-task`
