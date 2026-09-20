/*
 * Copyright (c) 2022 xjunz. All rights reserved.
 */

package top.xjunz.tasker.task.runtime

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.xjunz.shared.trace.logcat
import top.xjunz.tasker.engine.runtime.Event
import top.xjunz.tasker.engine.runtime.TaskRuntime
import top.xjunz.tasker.engine.runtime.TaskRuntime.Companion.obtainTaskRuntime
import top.xjunz.tasker.engine.runtime.ValueRegistry
import top.xjunz.tasker.engine.task.EventDispatcher
import top.xjunz.tasker.engine.task.TaskManager
import top.xjunz.tasker.engine.task.TaskScheduler
import top.xjunz.tasker.engine.task.XTask
import top.xjunz.tasker.service.isPremium
import top.xjunz.tasker.task.event.RemotePollEventDispatcher
import kotlin.coroutines.CoroutineContext
import kotlin.system.exitProcess

/**
 * @author xjunz 2022/08/05
 */
class ResidentTaskScheduler(private val taskManager: TaskManager<*, *>) : EventDispatcher.Callback,
    TaskScheduler<Array<Event>>() {

    companion object {
        const val MAX_ENABLED_RESIDENT_TASKS_FOR_NON_PREMIUM_USER = 3
    }

    override val taskType: Int = XTask.TYPE_RESIDENT

    override val coroutineContext: CoroutineContext =
        Dispatchers.Default + CoroutineName("ResidentTaskScope") + SupervisorJob()

    override fun suppressAll() {
        taskManager.getEnabledResidentTasks().forEach {
            it.halt(false)
        }
    }

    override fun scheduleTasks(
        tasks: List<XTask>,
        arg: Array<Event>,
        listener: XTask.TaskStateListener?
    ) {
        if (isSuppressed) return
        val registry = ValueRegistry()
        for (task in tasks) {
            check(task.metadata.taskType == taskType) {
                "Unsupported task type!"
            }
            if (!task.isExecuting || task.isSuspending) {
                val argHash = arg.contentHashCode()
                // Too hot, do not touch it now!
                if (task.isOverheat(argHash)) continue
                launch {
                    task.previousArgumentHash = argHash
                    task.setStateListener(listener)
                    task.launch(obtainTaskRuntime(task, registry, arg))
                }
            } else {
                task.getRuntime()?.onNewEvents(arg)
            }
        }
    }

    private fun getResidentTasks(): List<XTask> {
        val tasks = taskManager.getEnabledResidentTasks()
        if (!isPremium && tasks.size > MAX_ENABLED_RESIDENT_TASKS_FOR_NON_PREMIUM_USER) {
            exitProcess(-1)
        }
        return tasks
    }

    override fun onEvents(events: Array<Event>) {
        // 如果是远程触发事件，只调度匹配 checksum 的任务
        val remoteEvent = events.firstOrNull { it.type == RemotePollEventDispatcher.EVENT_ON_REMOTE_TRIGGER }
        if (remoteEvent != null) {
            val targetChecksum = try {
                remoteEvent.getExtra<Long>(RemotePollEventDispatcher.EXTRA_TASK_CHECKSUM)
            } catch (_: Exception) {
                0L
            }
            if (targetChecksum != 0L) {
                val matched = getResidentTasks().filter { it.checksum == targetChecksum }
                if (matched.isNotEmpty()) {
                    scheduleTasks(matched, events, makeReportingListener(targetChecksum))
                } else {
                    logcat("Remote trigger: no resident task found for checksum=$targetChecksum")
                }
                return
            }
        }
        scheduleTasks(getResidentTasks(), events, listener)
    }

    private fun makeReportingListener(taskChecksum: Long): XTask.TaskStateListener {
        return object : XTask.TaskStateListener {
            override fun onTaskStarted(runtime: TaskRuntime) {
                logcat("\n\n")
                logcat("******** $runtime Started (remote) [${Thread.currentThread()}] ********")
            }

            override fun onTaskError(runtime: TaskRuntime, t: Throwable) {
                logcat(t.stackTraceToString())
                logcat("-------- $runtime Error --------")
                RemotePollEventDispatcher.reportStatus(taskChecksum, false, t.message)
            }

            override fun onTaskFailure(runtime: TaskRuntime) {
                logcat("-------- $runtime Failure --------")
                RemotePollEventDispatcher.reportStatus(taskChecksum, false, "failure")
            }

            override fun onTaskSuccess(runtime: TaskRuntime) {
                logcat("-------- $runtime Success --------")
                RemotePollEventDispatcher.reportStatus(taskChecksum, true)
            }

            override fun onTaskCancelled(runtime: TaskRuntime) {
                logcat("-------- $runtime Cancelled --------")
                RemotePollEventDispatcher.reportStatus(taskChecksum, false, "cancelled")
            }
        }
    }

    private val listener = object : XTask.TaskStateListener {
        override fun onTaskStarted(runtime: TaskRuntime) {
            super.onTaskStarted(runtime)
            logcat("\n\n")
            logcat("******** $runtime Started[${Thread.currentThread()}] ********")
        }

        override fun onTaskError(runtime: TaskRuntime, t: Throwable) {
            super.onTaskError(runtime, t)
            logcat(t.stackTraceToString())
            logcat("-------- $runtime Error --------")
        }

        override fun onTaskFailure(runtime: TaskRuntime) {
            super.onTaskFailure(runtime)
            logcat("-------- $runtime Failure --------")
        }

        override fun onTaskSuccess(runtime: TaskRuntime) {
            super.onTaskSuccess(runtime)
            logcat("-------- $runtime Success --------")
        }

        override fun onTaskCancelled(runtime: TaskRuntime) {
            super.onTaskCancelled(runtime)
            logcat("-------- $runtime Cancelled --------")
        }
    }
}
