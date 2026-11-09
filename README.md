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

本次构建使用 AGP 8.9.2 + Gradle 8.14.3 + Room 2.6.1。APK 大小为 11,232,483 字节（约 10.7 MiB）；`:app:testDebugUnitTest` 的 3 项跟读评分测试全部通过。构建时 adb 无已连接设备，因此尚未执行真机安装和系统 TTS/语音识别实机验收。

## 内容与隐私

- 种子内容位于 `app/src/main/assets/seed.json`，首次启动事务性导入 Room。
- AI 对话与在线 TTS 均默认关闭，各自拥有 Key、BaseURL、模型与测试按钮。
- Key 只存本机加密存储，不回显、不写日志、不进入导出数据。
- 小思不申请系统悬浮窗权限；对话仅存本机，启用 AI 后仅发送必要上下文到用户自配接口。
