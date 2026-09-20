/*
 * Copyright (c) 2023 xjunz. All rights reserved.
 */

package top.xjunz.tasker.ui.main

import android.os.Bundle
import android.view.View
import top.xjunz.tasker.Preferences
import top.xjunz.tasker.R
import top.xjunz.tasker.databinding.DialogRemotePollBinding
import top.xjunz.tasker.ktx.textString
import top.xjunz.tasker.ktx.toast
import top.xjunz.tasker.ui.base.BaseDialogFragment
import top.xjunz.tasker.util.ClickListenerUtil.setNoDoubleClickListener

/**
 * 远程轮询设置对话框
 */
class RemotePollSettingsDialog : BaseDialogFragment<DialogRemotePollBinding>() {

    override val isFullScreen: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.apply {
            swEnabled.isChecked = Preferences.remotePollEnabled
            etServerUrl.setText(Preferences.remoteServerUrl.orEmpty())
            val intervalSec = (Preferences.remotePollIntervalMs / 1000L).coerceAtLeast(5L)
            etInterval.setText(intervalSec.toString())

            ibDismiss.setOnClickListener { dismiss() }
            btnNegative.setOnClickListener { dismiss() }

            btnPositive.setNoDoubleClickListener {
                val url = etServerUrl.textString.trim()
                val intervalText = etInterval.textString.trim()
                val intervalSecValue = intervalText.toLongOrNull()

                if (swEnabled.isChecked) {
                    if (url.isEmpty()) {
                        toastAndShake(R.string.error_empty_server_url)
                        return@setNoDoubleClickListener
                    }
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        toastAndShake(R.string.error_invalid_server_url)
                        return@setNoDoubleClickListener
                    }
                    if (intervalSecValue == null || intervalSecValue < 5) {
                        toastAndShake(R.string.error_poll_interval_too_short)
                        return@setNoDoubleClickListener
                    }
                }

                Preferences.remotePollEnabled = swEnabled.isChecked
                Preferences.remoteServerUrl = url.ifEmpty { null }
                if (intervalSecValue != null && intervalSecValue >= 5) {
                    Preferences.remotePollIntervalMs = intervalSecValue * 1000L
                }
                toast(R.string.saved)
                dismiss()
            }
        }
    }
}
