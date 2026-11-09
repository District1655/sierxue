package com.sierxue.english.update

import android.content.Context
import com.fyt.updater.GhUpdater
import com.fyt.updater.ReleaseInfo
import com.fyt.updater.UpdateCallback
import com.fyt.updater.UpdaterConfig
import com.sierxue.english.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class UpdateController(context: Context) {
    private val appContext = context.applicationContext
    private val config = UpdaterConfig.Builder("District1655/sierxue")
        .appName("sierxue")
        .currentVersion(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
        .expectedPackageName(BuildConfig.APPLICATION_ID)
        .baseUrl("")
        .logTag("SiErXueUpdater")
        .build()
    private val updater = GhUpdater(config)
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state

    private val callback = object : UpdateCallback {
        override fun onUpdateAvailable(release: ReleaseInfo) {
            val manual = _state.value.manual
            _state.value = _state.value.copy(
                checking = false,
                release = release,
                dialogVisible = true,
                message = if (manual) "发现 ${release.tagName}" else null,
            )
        }

        override fun onUpToDate() {
            _state.value = _state.value.copy(
                checking = false,
                message = if (_state.value.manual) "已是最新版本" else null,
            )
        }

        override fun onError(message: String) {
            val shouldShow = _state.value.manual || _state.value.downloading
            _state.value = _state.value.copy(
                checking = false,
                downloading = false,
                message = if (shouldShow) message else _state.value.message,
            )
        }

        override fun onDownloadProgress(downloadedBytes: Long, totalBytes: Long) {
            _state.value = _state.value.copy(downloadedBytes = downloadedBytes, totalBytes = totalBytes)
        }

        override fun onDownloadComplete() {
            _state.value = _state.value.copy(downloading = false, message = "已打开系统安装页面")
        }
    }

    fun check(manual: Boolean) {
        if (_state.value.checking || _state.value.downloading) return
        _state.value = UpdateState(
            checking = true,
            manual = manual,
            release = _state.value.release,
            message = if (manual) "正在检查更新…" else null,
        )
        updater.checkUpdate(callback)
    }

    fun startDownload() {
        val release = _state.value.release ?: return
        if (_state.value.downloading) return
        _state.value = _state.value.copy(
            downloading = true,
            dialogVisible = true,
            downloadedBytes = 0,
            totalBytes = release.downloadSize,
            message = null,
        )
        updater.downloadAndInstall(appContext)
    }

    fun cancelDownload() {
        updater.cancelDownload()
        _state.value = _state.value.copy(downloading = false, dialogVisible = false, message = "下载已取消")
    }

    fun dismiss() {
        if (!_state.value.downloading) _state.value = _state.value.copy(dialogVisible = false)
    }
}

data class UpdateState(
    val checking: Boolean = false,
    val manual: Boolean = false,
    val release: ReleaseInfo? = null,
    val dialogVisible: Boolean = false,
    val downloading: Boolean = false,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String? = null,
)
