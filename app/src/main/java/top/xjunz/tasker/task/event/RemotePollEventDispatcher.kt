/*
 * Copyright (c) 2023 xjunz. All rights reserved.
 * Modified for remote poll support.
 */

package top.xjunz.tasker.task.event

import android.os.Handler
import android.os.Looper
import androidx.core.os.HandlerCompat
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.xjunz.shared.trace.logcat
import top.xjunz.tasker.engine.runtime.Event
import top.xjunz.tasker.engine.task.EventDispatcher
import top.xjunz.tasker.isAppProcess

/**
 * 轮询远程服务器，根据接口返回决定是否触发任务，并在任务结束后上报状态。
 *
 * 注意：Shizuku 服务跑在独立进程，不能使用依赖 Application 的 [top.xjunz.tasker.Preferences]。
 * 配置通过 [updateConfig] 从主进程注入（连接成功时 / 设置页保存时）。
 *
 * 服务端接口约定：
 * - GET  {baseUrl}/tasks/pending
 * - POST {baseUrl}/tasks/report
 */
class RemotePollEventDispatcher(looper: Looper) : EventDispatcher() {

    companion object {
        const val EVENT_ON_REMOTE_TRIGGER = 100
        const val EXTRA_TASK_CHECKSUM = 0
        const val EXTRA_PAYLOAD = 1

        @Volatile
        var instance: RemotePollEventDispatcher? = null
            private set

        /** 是否启用（由主进程注入，默认关闭） */
        @Volatile
        var enabled: Boolean = false
            private set

        /** 服务器 baseUrl */
        @Volatile
        var serverUrl: String? = null
            private set

        /** 轮询间隔毫秒 */
        @Volatile
        var intervalMs: Long = 15_000L
            private set

        /**
 * 从主进程更新配置。Shizuku 进程内请通过 AIDL 调用后再落到这里。
         */
        @JvmStatic
        fun updateConfig(enabled: Boolean, serverUrl: String?, intervalMs: Long) {
            this.enabled = enabled
            this.serverUrl = serverUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            this.intervalMs = intervalMs.coerceAtLeast(5_000L)
            logcat("RemotePoll config: enabled=$enabled url=${this.serverUrl} interval=${this.intervalMs}")
        }

        fun reportStatus(taskChecksum: Long, success: Boolean, message: String? = null) {
            instance?.doReport(taskChecksum, success, message)
        }
    }

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        engine {
            requestTimeout = 10_000
        }
    }

    private val json: Json = Json { ignoreUnknownKeys = true }

    private val handler: Handler = HandlerCompat.createAsync(looper)

    private val pollRunnable: Runnable = object : Runnable {
        override fun run() {
            val interval: Long = intervalMs
            if (!enabled) {
                handler.postDelayed(this, interval)
                return
            }
            val baseUrl: String? = serverUrl
            if (baseUrl.isNullOrEmpty()) {
                handler.postDelayed(this, interval)
                return
            }
            val self: Runnable = this
            scope.launch {
                try {
                    val response = httpClient.get("$baseUrl/tasks/pending")
                    val body: String = response.bodyAsText()
                    if (response.status.value in 200..299 && body.isNotBlank()) {
                        val pending: PendingTaskResponse =
                            json.decodeFromString(PendingTaskResponse.serializer(), body)
                        if (pending.shouldExecute && pending.taskChecksum != 0L) {
                            val event = Event.obtain(EVENT_ON_REMOTE_TRIGGER)
                            event.putExtra(EXTRA_TASK_CHECKSUM, pending.taskChecksum)
                            event.putExtra(EXTRA_PAYLOAD, pending.payload ?: "")
                            dispatchEvents(event)
                            logcat("Remote poll: trigger task checksum=${pending.taskChecksum}")
                        }
                    }
                } catch (e: Exception) {
                    logcat("Remote poll error: ${e.message}")
                } finally {
                    handler.postDelayed(self, intervalMs)
                }
            }
        }
    }

    override fun onRegistered() {
        instance = this
        // 辅助功能模式与主进程同一进程，可尝试从 Preferences 同步（失败则忽略）
        if (isAppProcess) {
            try {
                val prefsClass = Class.forName("top.xjunz.tasker.Preferences")
                val enabledField = prefsClass.getDeclaredField("remotePollEnabled")
                // Preferences uses delegated properties; read via getters if present
                // Safer: only rely on updateConfig from UI / connect path
            } catch (_: Throwable) {
            }
        }
        handler.post(pollRunnable)
    }

    override fun destroy() {
        handler.removeCallbacksAndMessages(null)
        instance = null
        try {
            httpClient.close()
        } catch (_: Exception) {
        }
    }

    private fun doReport(taskChecksum: Long, success: Boolean, message: String?) {
        val baseUrl: String = serverUrl ?: return
        if (!enabled) return
        scope.launch {
            try {
                httpClient.post("$baseUrl/tasks/report") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StatusReport.serializer(),
                            StatusReport(taskChecksum, success, message)
                        )
                    )
                }
                logcat("Remote report: checksum=$taskChecksum success=$success")
            } catch (e: Exception) {
                logcat("Remote report error: ${e.message}")
            }
        }
    }

    @Serializable
    data class PendingTaskResponse(
        val shouldExecute: Boolean = false,
        val taskChecksum: Long = 0L,
        val payload: String? = null
    )

    @Serializable
    data class StatusReport(
        val taskChecksum: Long,
        val success: Boolean,
        val message: String? = null
    )
}
