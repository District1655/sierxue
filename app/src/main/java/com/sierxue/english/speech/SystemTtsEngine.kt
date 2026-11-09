package com.sierxue.english.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.sierxue.english.data.repository.SettingsRepository
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

enum class SystemTtsStatus { INITIALIZING, READY, DEGRADED, UNAVAILABLE }

data class TtsEngineDiagnostic(
    val packageName: String,
    val label: String,
    val versionName: String,
    val isDefault: Boolean,
    val enUsStatus: String = "未检测",
    val zhCnStatus: String = "未检测",
)

data class TtsDiagnostic(
    val available: Boolean = false,
    val defaultEngine: String = "未知",
    val defaultVersion: String = "未知",
    val engines: List<TtsEngineDiagnostic> = emptyList(),
    val selectedEngine: String = "未选择",
    val selectedLanguage: String = "未选择",
    val voiceDataStatus: String = "正在初始化",
    val lastOnInitCode: String = "尚未回调",
    val lastInitDurationMillis: Long? = null,
    val lastMessage: String = "",
    val queryServiceCount: Int = 0,
    val previousQueryServiceCount: String = "旧版未记录",
    val defaultEngineResolution: String = "尚未解析",
    val constructionStatus: String = "尚未构造",
) {
    fun asText(): String = buildString {
        append("系统 TTS 可用：").append(if (available) "是" else "否").append('\n')
        append("queryIntentServices(TTS_SERVICE) 返回数量：当前 ").append(queryServiceCount)
            .append("，上次 ").append(previousQueryServiceCount).append('\n')
        append("默认引擎解析结果：").append(defaultEngineResolution).append('\n')
        append("TextToSpeech 构造状态：").append(constructionStatus).append('\n')
        append("默认引擎：").append(defaultEngine).append("（").append(defaultVersion).append("）\n")
        append("当前引擎：").append(selectedEngine).append('\n')
        append("当前语言：").append(selectedLanguage).append('\n')
        append("语音数据：").append(voiceDataStatus).append('\n')
        append("最近 onInit：").append(lastOnInitCode).append("，耗时 ")
            .append(lastInitDurationMillis?.let { "$it ms" } ?: "未知").append('\n')
        if (lastMessage.isNotBlank()) append("最近结果：").append(lastMessage).append('\n')
        if (engines.isEmpty()) append("引擎列表：空（可能受包可见性限制或私有实现影响）")
        else engines.forEach {
            append("引擎：").append(it.packageName).append(" / ").append(it.label)
                .append(" / ").append(it.versionName)
                .append(if (it.isDefault) " / 默认" else "")
                .append(" / en-US=").append(it.enUsStatus)
                .append(" / zh-CN=").append(it.zhCnStatus).append('\n')
        }
    }.trim()

    fun asLogLine(): String = asText().replace('\n', '；').replace('\r', ' ')
}

class SystemTtsEngine(
    context: Context,
    private val settings: SettingsRepository,
) : SpeechEngine {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _status = MutableStateFlow(SystemTtsStatus.INITIALIZING)
    val status: StateFlow<SystemTtsStatus> = _status
    private val _diagnostic = MutableStateFlow(TtsDiagnostic())
    val diagnostic: StateFlow<TtsDiagnostic> = _diagnostic

    private var tts: TextToSpeech? = null
    private var callback: SpeechCallback? = null
    private var initJob: Job? = null

    init {
        reinitialize()
    }

    fun reinitialize() {
        initJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        _status.value = SystemTtsStatus.INITIALIZING
        _diagnostic.update {
            it.copy(
                available = false,
                engines = emptyList(),
                voiceDataStatus = "正在重新检测",
                lastMessage = "",
                constructionStatus = "尚未构造",
            )
        }
        initJob = scope.launch { initializeEngines() }
    }

    private suspend fun initializeEngines() {
        val previousCount = settings.settings.first().ttsServiceQueryCount
        val servicePackages = queryServicePackages()
        settings.setTtsServiceQueryCount(servicePackages.size)
        _diagnostic.update {
            it.copy(
                queryServiceCount = servicePackages.size,
                previousQueryServiceCount = if (previousCount < 0) "旧版未记录" else previousCount.toString(),
                voiceDataStatus = "优先尝试系统默认实例",
            )
        }

        // 隐式默认绑定是最可靠路径，不依赖 getEngines() 或默认引擎包名。
        val defaultResult = initializeCandidate(null)
        updateCandidateDiagnostic(null, defaultResult)
        val defaultPackage = defaultResult.defaultPackage
        val discoveredPackages = (defaultResult.enginePackages + servicePackages + defaultPackage)
            .filter { it.isNotBlank() }.distinct()
        updateEngineList(discoveredPackages, defaultPackage)
        if (defaultResult.instance != null && defaultResult.language != null) {
            finishSuccess(null, defaultResult, discoveredPackages)
            return
        }

        val preferred = settings.settings.first().preferredTtsEngine
        val ordered = if (preferred.isNotBlank()) {
            (listOf(preferred) + discoveredPackages).distinct()
        } else {
            (listOf(defaultPackage) + discoveredPackages).filter { it.isNotBlank() }.distinct()
        }
        if (ordered.isEmpty()) {
            finishUnavailable("默认实例初始化失败；引擎列表为空（可能受包可见性限制或私有实现影响），已尝试默认绑定")
            return
        }

        for (packageName in ordered) {
            _diagnostic.update { it.copy(selectedEngine = packageName, voiceDataStatus = "正在初始化 $packageName") }
            val result = initializeCandidate(packageName)
            updateCandidateDiagnostic(packageName, result)
            if (result.instance != null && result.language != null) {
                finishSuccess(packageName, result, discoveredPackages)
                return
            }
        }
        finishUnavailable("默认实例及 ${ordered.size} 个已发现引擎均初始化失败，可检查系统 TTS 输出或配置在线 TTS")
    }

    private suspend fun initializeCandidate(packageName: String?): CandidateResult {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            withContext(Dispatchers.Main) {
                withTimeout(INIT_TIMEOUT_MS) {
                    suspendCancellableCoroutine<CandidateResult> { continuation ->
                        val completed = AtomicBoolean(false)
                        var candidate: TextToSpeech? = null
                        fun finish(result: CandidateResult) {
                            if (completed.compareAndSet(false, true)) continuation.resume(result)
                        }
                        val listener = TextToSpeech.OnInitListener { code ->
                            mainHandler.post {
                                val instance = candidate
                                val duration = SystemClock.elapsedRealtime() - startedAt
                                if (instance == null) {
                                    finish(CandidateResult(
                                        code = "NPE", durationMillis = duration,
                                        message = "引擎实例为空", constructionStatus = "构造返回空实例（NPE）",
                                    ))
                                    return@post
                                }
                                val enginePackages = if (packageName == null) {
                                    runCatching { instance.engines.map { it.name } }.getOrDefault(emptyList())
                                } else emptyList()
                                val defaultPackage = if (packageName == null) {
                                    runCatching { instance.defaultEngine.orEmpty() }.getOrDefault("")
                                } else ""
                                val resolution = if (packageName == null) {
                                    "onInit=$code，getDefaultEngine=${defaultPackage.ifBlank { "返回空" }}"
                                } else ""
                                val construction = if (packageName == null) "无引擎名构造成功，onInit=$code" else "构造成功，onInit=$code"
                                if (code != TextToSpeech.SUCCESS) {
                                    instance.shutdown()
                                    finish(CandidateResult(
                                        code = code.toString(), durationMillis = duration,
                                        message = "onInit 返回 $code", constructionStatus = construction,
                                        defaultPackage = defaultPackage, enginePackages = enginePackages,
                                        defaultResolution = resolution,
                                    ))
                                    return@post
                                }
                                val enUs = safeLanguageAvailability(instance, Locale.US)
                                val zhCn = safeLanguageAvailability(instance, Locale.CHINA)
                                val language = selectLanguage(instance)
                                if (language == null) {
                                    instance.shutdown()
                                    finish(CandidateResult(
                                        code = code.toString(), durationMillis = duration,
                                        enUs = languageName(enUs), zhCn = languageName(zhCn),
                                        message = "没有可用语言数据", constructionStatus = construction,
                                        defaultPackage = defaultPackage, enginePackages = enginePackages,
                                        defaultResolution = resolution,
                                    ))
                                } else {
                                    finish(CandidateResult(
                                        instance = instance, language = language, code = code.toString(),
                                        durationMillis = duration, enUs = languageName(enUs), zhCn = languageName(zhCn),
                                        constructionStatus = construction, defaultPackage = defaultPackage,
                                        enginePackages = enginePackages, defaultResolution = resolution,
                                    ))
                                }
                            }
                        }
                        candidate = if (packageName == null) {
                            TextToSpeech(appContext, listener)
                        } else {
                            TextToSpeech(appContext, listener, packageName)
                        }
                        continuation.invokeOnCancellation {
                            if (completed.compareAndSet(false, true)) candidate?.shutdown()
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            CandidateResult(
                code = "TIMEOUT", durationMillis = SystemClock.elapsedRealtime() - startedAt,
                message = "onInit 超过 5 秒", constructionStatus = "构造成功，但 onInit 超过 5 秒",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val detail = "${error.javaClass.simpleName}: ${error.message ?: "无错误消息"}"
            CandidateResult(
                code = "EXCEPTION", durationMillis = SystemClock.elapsedRealtime() - startedAt,
                message = detail, constructionStatus = detail,
            )
        }
    }

    private fun updateCandidateDiagnostic(packageName: String?, result: CandidateResult) {
        _diagnostic.update { current ->
            val isDefault = packageName == null
            current.copy(
                defaultEngine = if (isDefault) result.defaultPackage.ifBlank { "未返回包名" } else current.defaultEngine,
                defaultVersion = if (isDefault) packageVersion(result.defaultPackage) else current.defaultVersion,
                defaultEngineResolution = if (isDefault) result.defaultResolution.ifBlank { result.message } else current.defaultEngineResolution,
                constructionStatus = if (isDefault) result.constructionStatus else current.constructionStatus,
                selectedEngine = packageName ?: result.defaultPackage.ifBlank { "系统默认（隐式绑定）" },
                engines = current.engines.map {
                    if (it.packageName == packageName) it.copy(enUsStatus = result.enUs, zhCnStatus = result.zhCn) else it
                },
                lastOnInitCode = result.code,
                lastInitDurationMillis = result.durationMillis,
                voiceDataStatus = if (result.language == null) result.message else "语音数据可用",
                lastMessage = result.message,
            )
        }
    }

    private fun updateEngineList(packages: List<String>, defaultPackage: String) {
        _diagnostic.update { current ->
            current.copy(engines = packages.map { packageName ->
                val old = current.engines.firstOrNull { it.packageName == packageName }
                TtsEngineDiagnostic(
                    packageName = packageName,
                    label = packageLabel(packageName),
                    versionName = packageVersion(packageName),
                    isDefault = packageName == defaultPackage,
                    enUsStatus = old?.enUsStatus ?: "未检测",
                    zhCnStatus = old?.zhCnStatus ?: "未检测",
                )
            })
        }
    }

    private fun finishSuccess(packageName: String?, result: CandidateResult, discoveredPackages: List<String>) {
        val instance = result.instance ?: return
        val language = result.language ?: return
        tts = instance
        installUtteranceListener(instance)
        val degraded = language == Locale.ROOT
        _status.value = if (degraded) SystemTtsStatus.DEGRADED else SystemTtsStatus.READY
        _diagnostic.update {
            it.copy(
                available = true,
                selectedEngine = packageName ?: result.defaultPackage.ifBlank { "系统默认（隐式绑定）" },
                selectedLanguage = language.toLanguageTag().ifBlank { "ROOT" },
                voiceDataStatus = if (degraded) "英语数据不可用，已回落系统默认语言" else "语音数据可用",
                lastMessage = if (discoveredPackages.isEmpty()) "默认实例初始化成功；引擎列表仍为空，但不影响朗读" else "初始化成功",
            )
        }
        Log.i(TAG, _diagnostic.value.asLogLine())
    }

    private fun selectLanguage(instance: TextToSpeech): Locale? {
        listOf(Locale.US, Locale.UK, Locale.ROOT).forEach { locale ->
            val available = safeLanguageAvailability(instance, locale)
            if (available >= TextToSpeech.LANG_AVAILABLE) {
                val result = runCatching { instance.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                if (result >= TextToSpeech.LANG_AVAILABLE) return locale
            }
        }
        return null
    }

    private fun safeLanguageAvailability(instance: TextToSpeech, locale: Locale): Int =
        runCatching { instance.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)

    private fun finishUnavailable(message: String) {
        _status.value = SystemTtsStatus.UNAVAILABLE
        _diagnostic.update { it.copy(available = false, voiceDataStatus = message, lastMessage = message) }
        Log.i(TAG, _diagnostic.value.asLogLine())
    }

    private fun installUtteranceListener(instance: TextToSpeech) {
        instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { callback?.onStarted(id) }
            override fun onDone(id: String) { callback?.onFinished(id) }
            @Deprecated("Android 旧版本回调")
            override fun onError(id: String) { callback?.onError(id, "系统发音失败") }
            override fun onError(id: String, errorCode: Int) { callback?.onError(id, "系统发音失败（$errorCode）") }
        })
    }

    override suspend fun start(request: SpeechRequest, callback: SpeechCallback) {
        status.filter { it != SystemTtsStatus.INITIALIZING }.first()
        val instance = tts
        if (status.value == SystemTtsStatus.UNAVAILABLE || instance == null) {
            throw IllegalStateException("系统 TTS 不可用，可在设置中检查")
        }
        withContext(Dispatchers.Main) {
            this@SystemTtsEngine.callback = callback
            instance.setSpeechRate(request.rate.coerceIn(0.7f, 1.3f))
            val result = instance.speak(request.text, TextToSpeech.QUEUE_FLUSH, Bundle(), request.id)
            if (result == TextToSpeech.ERROR) throw IllegalStateException("系统发音启动失败")
        }
    }

    override fun stop() {
        tts?.stop()
    }

    fun openSystemSettings(context: Context): Boolean {
        val intents = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA),
            Intent(Settings.ACTION_SETTINGS),
        )
        intents.forEach { intent ->
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // 尝试下一个系统入口。
            }
        }
        return false
    }

    private fun queryServicePackages(): List<String> = runCatching {
        appContext.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0,
        ).mapNotNull { it.serviceInfo?.packageName }
    }.getOrDefault(emptyList())

    private fun packageLabel(packageName: String): String = runCatching {
        appContext.packageManager.getApplicationInfo(packageName, 0).loadLabel(appContext.packageManager).toString()
    }.getOrDefault(packageName)

    @Suppress("DEPRECATION")
    private fun packageVersion(packageName: String): String {
        if (packageName.isBlank()) return "未知"
        return runCatching {
            appContext.packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
        }.getOrDefault("未知")
    }

    private fun languageName(code: Int): String = when (code) {
        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "国家/地区变体可用"
        TextToSpeech.LANG_COUNTRY_AVAILABLE -> "国家可用"
        TextToSpeech.LANG_AVAILABLE -> "语言可用"
        TextToSpeech.LANG_MISSING_DATA -> "缺少数据"
        TextToSpeech.LANG_NOT_SUPPORTED -> "不支持"
        else -> "未知($code)"
    }

    private data class CandidateResult(
        val instance: TextToSpeech? = null,
        val language: Locale? = null,
        val code: String,
        val durationMillis: Long,
        val enUs: String = "未检测",
        val zhCn: String = "未检测",
        val message: String = "",
        val constructionStatus: String = "尚未构造",
        val defaultPackage: String = "",
        val enginePackages: List<String> = emptyList(),
        val defaultResolution: String = "",
    )

    private companion object {
        const val INIT_TIMEOUT_MS = 5_000L
        const val TAG = "SiErXueTts"
    }
}
