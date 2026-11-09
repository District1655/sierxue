# Qwen3 Android 本地推理可行性验证

这是独立于思而学主 App 的最小验证工程，不被主工程 `settings.gradle.kts` 引用。用于验证 llama.cpp 在 Android arm64 上加载 GGUF、生成文本，并采集性能数据。

## 固定选型

- 模型：Qwen3-0.6B（指令模型），GGUF。
- 官方来源：https://huggingface.co/Qwen/Qwen3-0.6B-GGUF
- 当前官方仓库只提供 `Qwen3-0.6B-Q8_0.gguf`，没有官方 Q4_K_M，因此本验证不使用第三方转存。
- 文件大小：639,446,688 字节。
- SHA256：`9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031`
- 引擎：llama.cpp，Android NDK arm64；CPU 与 Vulkan 分开构建，Vulkan 不可用时使用 CPU。
- 模型和原生 `.so` 均为本地产物，不进入 Git 仓库。

## 构建

准备 Android NDK、CMake、Ninja 和 llama.cpp 源码，通过 `LLAMA_CPP_ROOT` 指定源码目录。原生构建使用 `android.toolchain.cmake`、`arm64-v8a` 和静态 C++ 标准库；CPU 使用 `android-26`，Vulkan 因 `vkGetPhysicalDeviceFeatures2` 的导出要求使用 `android-28`。本次验证的 llama.cpp 源码对应提交 `7d7f0287530bc8981690f361d8c1627dbae1c99a`。

应用包含两个 flavor：

- `cpu`：加载 `libllama_probe_cpu.so`，输出 `app-cpu-debug.apk`。
- `vulkan`：加载 `libllama_probe_vulkan.so`，显式将全部模型层卸载到 GPU，输出 `app-vulkan-debug.apk`。系统声明支持 Vulkan 不等于实际推理成功，仍以真机生成结果为准。

## 实测步骤

1. 将官方 GGUF 下载到电脑或手机，核对上面的 SHA256。
2. 安装对应后端 APK，打开“本地模型验证”。
3. 点击“选择 GGUF 模型”，通过系统文件选择器导入；应用会显示文件大小和 SHA256。
4. 保持应用在前台，输入一句 prompt 并开始生成。
5. 记录界面输出的模型加载耗时、首 token 延迟、生成速度、Native Heap 采样峰值、进程 CPU 估算和电池温度。
6. 点击“复制实测结果”保存反馈；结果同时写入应用私有外部目录的 `last_probe_result.txt`。
7. 先测 Vulkan。若无法加载、无回复或崩溃，卸载后改装 CPU flavor，对比同一模型和同一 prompt。

## 指标口径与限制

- 首 token 延迟不包含模型文件加载时间，模型加载耗时单列。
- 速度按首 token 后的生成 token/秒计算，最多生成 96 tokens，线程数为 4，上下文为 1024。
- 内存是生成期间每 50ms 采样的 Native Heap 峰值，不等同于整个系统峰值。
- CPU 是 `/proc/self/stat` 换算的单核口径，多线程时可能超过 100%。
- 电池温度来自系统 BatteryManager，仅用于观察升温趋势，不能替代实验室热测试。
- 当前无 adb 设备时，只能报告编译产物，不能声称已有小米 14 的真实性能数据。
