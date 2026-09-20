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
import top.xjunz.tasker.Preferences
import top.xjunz.tasker.engine.runtime.Event
import top.xjunz.tasker.engine.task.EventDispatcher

/**
 * 轮询远程服务器，根据接口返回决定是否触发任务，并在任务结束后上报状态。
 *
 * 服务端接口约定（可按需调整）：
 * - GET  {baseUrl}/tasks/pending
 *   返回 JSON: { "shouldExecute": true/false, "taskChecksum": 1234567890, "payload": "可选" }
 * - POST {baseUrl}/tasks/report
 *   Body JSON: { "taskChecksum": 1234567890, "success": true/false, "message": "可选" }
 */
class RemotePollEventDispatcher(looper: Looper) : EventDispatcher() {

    companion object {
        const val EVENT_ON_REMOTE_TRIGGER = 100
        const val EXTRA_TASK_CHECKSUM = 0
        const val EXTRA_PAYLOAD = 1

        @Volatile
        var instance: RemotePollEventDispatcher? = null
            private set

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

    /** Explicit [Runnable] type avoids recursive type-checking when self-scheduling. */
    private val pollRunnable: Runnable = object : Runnable {
        override fun run() {
            val intervalMs: Long = Preferences.remotePollIntervalMs.coerceAtLeast(5_000L)
            if (!Preferences.remotePollEnabled) {
                handler.postDelayed(this, intervalMs)
                return
            }
            val baseUrl: String? = Preferences.remoteServerUrl?.trim()?.trimEnd('/')
            if (baseUrl.isNullOrEmpty()) {
                handler.postDelayed(this, intervalMs)
                return
            }
            // Capture the Runnable for use inside the coroutine (this would be wrong there)
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
                    val nextInterval: Long =
                        Preferences.remotePollIntervalMs.coerceAtLeast(5_000L)
                    handler.postDelayed(self, nextInterval)
                }
            }
        }
    }

    override fun onRegistered() {
        instance = this
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
        val baseUrl: String = Preferences.remoteServerUrl?.trim()?.trimEnd('/') ?: return
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
