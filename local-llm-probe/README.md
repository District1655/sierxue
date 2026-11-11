# Qwen3 Android 本地推理可行性验证

这是独立于思而学主 App 的最小验证工程，不被主工程 `settings.gradle.kts` 引用。用于验证 llama.cpp 在 Android arm64 上加载 GGUF、生成文本，并采集性能、内存、温度与功耗相关数据。主 App 的云端和 429 稳定性逻辑不受本工程影响。

## 模型与引擎

- 引擎：llama.cpp，对应提交 `7d7f0287530bc8981690f361d8c1627dbae1c99a`；CPU 与 Vulkan flavor 分开，Vulkan 不可用时使用 CPU。
- 官方 Q8 基线：`Qwen/Qwen3-0.6B-GGUF`，文件 `Qwen3-0.6B-Q8_0.gguf`，639,446,688 字节，SHA256 `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031`。
- 可信社区 Q4 对照：`unsloth/Qwen3-0.6B-GGUF`，固定修订 `50968a4468ef4233ed78cd7c3de230dd1d61a56b`，文件 `Qwen3-0.6B-Q4_K_M.gguf`，396,705,472 字节，SHA256 `ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a`。
- Q4 文件校验值与 Hugging Face `X-Linked-ETag` 一致。首选 bartowski 仓库本次未能读取，因此按预定顺序使用 unsloth；没有使用来源不明的网盘转存。
- 模型和原生 `.so` 均为本地产物，不进入 Git 仓库。

## 生成与思考模式

- 当前 llama.cpp 支持 `common_chat_templates_inputs.enable_thinking`，验证工程直接通过该参数控制 Qwen3 模板，不需要 `/no_think` 替代。
- 「深度思考」默认关闭并通过 SharedPreferences 持久化。开启后保留完整 reasoning，UI 默认折叠；关闭时不显示思考区块，展示层会剥离带标记的思考段。
- 默认最大生成 token 为「不限」，可手动选择 512 / 1024 / 2048。循环按 `n_predict=-1` 的等价方式运行，直到 EOS / `<|im_end|>`；4096 上下文耗尽或手动达到上限时会明确标注未自然结束。
- 当前参数：上下文 4096、batch 512、线程 4、greedy。连续任务复用模型会话并清空上一轮 KV，不重复加载模型；后续任务的 `模型加载=0 ms` 表示复用会话。
- 结果页不设置行数上限或省略号，长文可滚动、选择并复制全文。停止原因、实际 token 数和未自然结束警告均可见。

## 压测与采样

- 「连续长任务压测 3 次」使用同一固定 prompt，列出三次性能及相对第 1 次的速度变化。
- 定时模式支持 1 / 3 / 5 / 10 分钟和不限时长手动停止；长任务自动轮转，原生取消可在到点或手动停止时中断当前生成。
- 压测期间保持屏幕常亮并启动前台服务，结束或停止后解除。
- 每 500 ms 采样 Native Heap、Java Heap、PSS/USS、系统可用内存、CPU、温度、电池百分比，以及可读取的电流、电荷计和电压。不可读字段在 CSV 中留空并说明原因，不用 0 冒充。
- PSS 优先读取 `/proc/self/smaps_rollup`，不可用时使用 `Debug.MemoryInfo`；USS 只由 `Private_Clean + Private_Dirty` 计算，无法读取时明确标为不可用。
- 功耗依次尝试 BatteryManager 电流/电荷计、sysfs 电流/电压、电荷计前后差值。全部不可用时，明确标注「本机无法读取电流，功耗以温度与 CPU 推断」。电流符号方向因机型而异，统计使用绝对值。
- 报告按分钟列出速度、温度、Native Heap、CPU 和累计 token；前 30 秒为速度基线，后续衰减超过 30% 标注「疑似降频」，这只是软件迹象，不等同于已证明 thermal throttling。
- 私有外部目录保存 `last_stress_report.txt`、`last_stress_samples.csv`。可复制完整报告与 CSV，也可导出 CSV 或包含设备信息、模型信息、`/proc/meminfo` 摘要的 ZIP 诊断包；精确功耗仍需在电脑端结合 `dumpsys batterystats` 分析。

## 构建

准备 Android NDK、CMake、Ninja 和 llama.cpp 源码，通过 `LLAMA_CPP_ROOT` 指定源码目录。原生构建使用 `android.toolchain.cmake`、`arm64-v8a` 和静态 C++ 标准库；CPU 使用 `android-26`，Vulkan 因 `vkGetPhysicalDeviceFeatures2` 的导出要求使用 `android-28`。

- `cpu`：加载 `libllama_probe_cpu.so`，输出 `app-cpu-debug.apk`。
- `vulkan`：加载 `libllama_probe_vulkan.so`，显式将全部模型层卸载到 GPU，输出 `app-vulkan-debug.apk`。系统声明支持 Vulkan 不等于实际推理成功。

## 简明实测步骤

1. 安装 v1.3.0 CPU 包，先核对首页版本号和构建时间，不要使用没有「深度思考」开关和压测入口的旧包。
2. 导入 Q8_0，确认原始文件名、量化方式和 SHA256。保持思考关闭、长度「不限」，用默认 prompt「用一句中文介绍英语学习助手小思。」运行并复制全文。
3. 开启思考，用同一 prompt 再运行，确认「思考过程（已折叠）+ 完整正文」，记录思考耗时、正文生成耗时和总耗时。
4. 重新导入 Q4_K_M，重复步骤 2、3。Q8 / Q4 必须使用同一 APK、prompt、思考开关和长度设置，才能比较。
5. 选择 5 分钟定时压测，保持应用前台并等待自动停止；导出报告、CSV 和诊断包，检查分钟分段、最高温度、内存趋势和疑似降频标记。
6. Vulkan 另装同版本 Vulkan 包，用同一模型和 prompt 先做一次普通生成。若原生库加载失败、模型加载崩溃或生成崩溃，先保留错误，卸载 Vulkan 后改装 CPU 包并重新导入模型，不要反复触发崩溃。

## 旧版实测与质量基线

以下数据来自用户的小米 14（23127PN0CC，Android 16 / OS3.0.307.0.WNCCNXM），**均为旧版 1.0，无法用于验证本轮修复**：

- 第一轮：Q8_0 / CPU，加载 1473 ms，首 token 1692 ms，6.44 token/s，96 token，总耗时约 18.1 秒，Native Heap 峰值 306.6 MiB，CPU 260%，电池 40.3℃ 未上升。
- 第二轮：CPU，加载 736 ms，首 token 205 ms，45.89 token/s，96 token，总耗时 3132 ms，Native Heap 峰值 308.3 MiB，CPU 315%，电池 38.7℃ 无变化。第二轮未提供模型 SHA、后台任务或冷/热启动记录。
- 两轮旧包均为上下文 1024、batch 128、线程 4、greedy、96 token，没有构建参数 diff。第二轮加载和首 token 变快符合缓存变热的一种表现，但证据不足；**6.44 → 45.89 token/s 的原因未确定**。新版改为 4096 / 512 / 不限长度并复用会话，不应与旧速度直接作因果比较。
- 旧版输出把《孤勇者》错误归为周杰伦作品，并将《告白气球》错误写成《告白》。这证明 0.6B 不适合自由事实性问答；后续主 App 若接入，只能基于给定教材做改写、出题和讲解，prompt 必须注入教材上下文并禁止自由发挥。Q4 是否出现中文夹生、格式跑偏或答非所问尚未实测，当前不能给出是否建议降到 Q4 的结论。

新版 v1.3.0 的思考抑制、自然 EOS、完整正文、Q4 对照、Vulkan 和 5 分钟压测均**尚未真机验证**。当前没有 adb 设备，因此这里只记录编译产物和用户回传的旧数据，不把推断写成实测结果。
