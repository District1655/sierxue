# 思而学（SiErXue）

思而学是一款教材驱动、离线优先的英语学习 Android App。课程按「4 册 → 每册 8 课 → 每课固定五段」组织，首版完整内置入门 A1 八课，其余分册提供课程目录与简介。练习、错题、复习、测评、五维能力和全局学习助手「小思」均服务于课程主线。

## 技术栈

- Kotlin 1.9.22、Jetpack Compose Material 3
- 单 Activity + Compose Navigation，MVVM + Repository
- Room 内容/学习记录/小思会话，DataStore 非敏感设置
- EncryptedSharedPreferences 保存两组独立 API Key
- OkHttp 调用 OpenAI 兼容对话与 TTS 接口
- 系统 TextToSpeech 为默认发音，在线 TTS 为可降级增强

详细规则见 [产品与内容设计](docs/产品与内容设计.md)。版本路线已确定：阶段 B 扩充内容与趋势，阶段 C 接入 Qwen3-0.6B + llama.cpp Vulkan/CPU，阶段 D 接入 sherpa-onnx 中英离线发音；本地模型和语音资源均按需下载、不打进 APK。

## 构建

本项目不依赖 Gradle Wrapper 下载发行包。准备 JDK 17、Gradle 8.14.3 和 Android SDK 34 后，将以下占位路径替换为自己的安装位置：

```powershell
$env:JAVA_HOME = "<JDK 17 安装目录>"
$env:ANDROID_HOME = "<Android SDK 目录>"
& "<Gradle 8.14.3 安装目录>\bin\gradle.bat" :app:assembleDebug --no-daemon
```

在本机创建不纳入版本控制的 `local.properties`，写入 `sdk.dir=<Android SDK 目录>`。首次解析 Compose、Navigation、DataStore 等依赖需要联网；后续可使用 `--offline` 构建。

Debug APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。应用包名为 `com.sierxue.english`，启动图标为自适应图标配合 SX 字母矢量占位方案，无外部图片素材。

本次构建为 v1.0.3（versionCode 10003），使用 AGP 8.9.2 + Gradle 8.14.3 + Room 2.6.1。APK 大小为 11,854,854 字节（约 11.3 MiB），SHA256 为 `AEED5C3AE2CDD32CA9200EF897175B982F12FD43A532A542AA4670BEA70267FB`；`:app:testDebugUnitTest` 的 7 项测试全部通过。构建时 adb 无已连接设备，因此尚未执行真机、真 Key 限流恢复、流式中断续写、系统 TTS/语音识别和在线更新实机验收。

## 在线更新

应用启动后延迟静默检查一次，也可在设置页手动检查。更新源为 `District1655/sierxue` 的 GitHub Releases。下载显示进度并支持取消；APK 在私有缓存中校验包名和 versionCode 后，通过 FileProvider 打开系统安装页面，不会静默安装。

本工程将通用库源码 vendor 在 `third_party/gh-updater`，来源为 `District1655/gh-updater`。本仓库内的改动包括 10 秒超时、版本化资产选择、私有缓存、进度/取消、APK 安全校验、FileProvider 和未知来源安装引导；`baseUrl` 默认留空，使用官方 API 与系统代理，不内置镜像或 Token。

## AI 多端点与 429 稳定性

对话支持 1～5 个端点、在线 TTS 支持 1～3 个端点。端点按设置页顺序切换，可上下移动、指定主力、独立启用和设置 10～60 次/分钟；旧单端点及加密 Key 自动迁移。通道健康提供最近成功、错误摘要、连续失败、最近 429、令牌桶余量、冷却状态和手动恢复。

| 项目 | 当前参数 |
|---|---|
| 短时 429 | 同端点最多 5 次，等待 1s/2s/3s/4s；连续 3 次后轮换，跨端点最多 8 次或约 20 秒 |
| Retry-After | 支持秒、HTTP 日期和 OpenAI reset 时长；≤8 秒优先遵循，>8 秒走短间隔/轮换 |
| 429 冷却 | 90 秒后自动半开恢复 |
| 网络/5xx | 单端点最多 3 次，1 秒基数指数退避、上限 8 秒并加抖动；连续失败冷却 10 分钟 |
| 配置错误 | 400/401/403/404/422 不盲目重试 |
| 额度耗尽 | insufficient_quota / billing 等停止该端点，需手动恢复 |
| 请求超时 | callTimeout 25 秒；流式首字节/读取间隔 12 秒 |
| 流式中断 | 保留已输出内容并标注中断，提供“继续”，不从头重发 |
| 令牌桶 | 默认 20 次/分钟，仅预先节流批量/自动任务；429 重试不额外扣减 |
| 缓存 | 纯解释/翻译按问题 + 上下文缓存 5 分钟，出题不缓存 |

默认 20 次/分钟是否偏保守需真 Key 实测后调整。非实时批量任务使用 WorkManager 在网络恢复后串行重试，实时对话不后台补发。全部端点失败时显示「离线回答」并使用本地课程内容，不打断学习。

## 常见问题

### 小米 / 澎湃 OS 提示系统 TTS 不可用

思而学会优先使用不指定引擎包名的系统默认 `TextToSpeech` 实例，失败或 5 秒超时后再遍历其他已安装引擎。英语按 `en-US → en-GB → ROOT` 回落，不会因某一种英语语音数据缺失就直接终止全部发音。

**Android 11+ 引擎列表为空时，优先检查包可见性。** v1.0.2 在清单中仅声明 `android.intent.action.TTS_SERVICE` 查询，不申请宽泛包可见性。诊断页会显示 `queryIntentServices(TTS_SERVICE)` 的本次/上次返回数量、默认引擎解析结果和构造异常。空列表不再直接等于“系统无 TTS”：默认实例仍会先尝试；若补声明后仍为空，再检查 HyperOS 的应用权限/自启动限制、系统“文字转语音”输出是否启用，以及小米私有引擎是否不响应标准服务查询。

进入「设置 → 发音诊断」可以查看并复制默认引擎、版本、引擎列表、en-US/zh-CN 数据状态、当前语言、最近 onInit 返回码和耗时，也可以重新初始化、测试朗读 Hello、前往系统设置或手动切换引擎。手动选择的引擎会持久化。若系统 TTS 全部不可用，喇叭仅降级为不可用状态，课程不受阻塞；已配置的在线 TTS 会继续独立工作。

## 发布新版本

1. 在 `app/build.gradle.kts` 递增 `versionCode` 并更新 `versionName`。更新库采用 `X*10000 + Y*100 + Z` 的 versionCode 约定。
2. 执行 `:app:assembleDebug :app:testDebugUnitTest`，确认构建和测试通过。
3. 将生成的 APK 重命名为 `sierxue-<version>-debug.apk`（发布正式包时可命名为 `sierxue-<version>.apk`），不要提交到源码仓库。
4. 在 GitHub 创建 Release，tag 使用 `vX.Y.Z`，填写更新说明并上传 APK。应用会在多资产时优先选择与版本号匹配的 APK，否则回退到应用名匹配项或第一个 APK。

## 内容与隐私

- 种子内容位于 `app/src/main/assets/seed.json`，首次启动事务性导入 Room。
- AI 对话与在线 TTS 均默认关闭，各自拥有 Key、BaseURL、模型与测试按钮。
- Key 只存本机加密存储，不回显、不写日志、不进入导出数据。
- 小思不申请系统悬浮窗权限；对话仅存本机，启用 AI 后仅发送必要上下文到用户自配接口。
