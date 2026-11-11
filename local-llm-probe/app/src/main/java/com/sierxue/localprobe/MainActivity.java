package com.sierxue.localprobe;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private static final int PICK_GGUF = 42;
    private static final int EXPORT_CSV = 43;
    private static final int EXPORT_ZIP = 44;
    private static final String OFFICIAL_Q8_SHA256 = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031";
    private static final String Q4_K_M_SHA256 = "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a";
    private static final String[] STRESS_PROMPTS = {
            "用 20 个句子讲解一般现在时，并各配一个例句。",
            "围绕周末学习计划写约 300 词英语短文，并逐句给出中文解释。",
            "选择 10 个描述日常习惯的英语动词，各造一个句子并说明常用搭配。",
            "用 20 个句子讲解一般过去时，并各配一个例句。",
            "围绕英语学习计划写约 300 词英语短文，并逐句解释。",
            "为 10 个校园高频词各造一个句子并说明搭配。"
    };
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView result;
    private EditText prompt;
    private ProgressBar progress;
    private Button generate;
    private Switch deepThinking;
    private Spinner maxTokens;
    private Button thinkingToggle;
    private TextView thinkingContent;
    private File modelFile;
    private String exportReport = "";
    private String stressCsv = "";
    private volatile boolean stressActive = false;
    private StressMonitor stressMonitor;
    private Spinner stressDuration;
    private Button stressThree;
    private Button stressTimed;
    private Button stressStop;
    private File pendingExportFile;
    private static String loadError = "";

    static {
        try {
            System.loadLibrary(BuildConfig.NATIVE_LIBRARY_NAME);
        } catch (Throwable error) {
            loadError = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modelFile = new File(getFilesDir(), "model.gguf");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("Qwen3 本地模型可行性验证\n版本 " + BuildConfig.VERSION_NAME + " / " + BuildConfig.BUILD_TIME
                + "\n" + Build.MODEL + " / Android " + Build.VERSION.RELEASE);
        title.setTextSize(20);
        root.addView(title);

        status = new TextView(this);
        status.setPadding(0, 20, 0, 20);
        root.addView(status);

        Button pick = new Button(this);
        pick.setText("选择 GGUF 模型（不打包进 APK）");
        pick.setOnClickListener(v -> selectModel());
        root.addView(pick);

        prompt = new EditText(this);
        prompt.setHint("输入一句 prompt");
        prompt.setText("用一句中文介绍英语学习助手小思。");
        prompt.setMinLines(2);
        root.addView(prompt);

        deepThinking = new Switch(this);
        deepThinking.setText("深度思考（开启后回答更慢）");
        deepThinking.setChecked(preferences().getBoolean("deep_thinking", false));
        deepThinking.setOnCheckedChangeListener((view, checked) ->
                preferences().edit().putBoolean("deep_thinking", checked).apply());
        root.addView(deepThinking);

        TextView limitLabel = new TextView(this);
        limitLabel.setText("最大生成 token（默认不限，受 4096 上下文约束）");
        root.addView(limitLabel);
        maxTokens = new Spinner(this);
        ArrayAdapter<String> limits = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"不限", "512", "1024", "2048"});
        limits.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        maxTokens.setAdapter(limits);
        maxTokens.setSelection(0);
        root.addView(maxTokens);

        generate = new Button(this);
        generate.setText("加载模型并生成");
        generate.setOnClickListener(v -> runGeneration());
        root.addView(generate);

        stressThree = new Button(this);
        stressThree.setText("连续长任务压测 3 次");
        stressThree.setOnClickListener(v -> startStress(false));
        root.addView(stressThree);

        TextView stressLabel = new TextView(this);
        stressLabel.setText("定时连续压测（任务自动轮转）");
        root.addView(stressLabel);
        stressDuration = new Spinner(this);
        ArrayAdapter<String> durations = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"1 分钟", "3 分钟", "5 分钟", "10 分钟", "不限时长，手动停止"});
        durations.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        stressDuration.setAdapter(durations);
        stressDuration.setSelection(2);
        root.addView(stressDuration);

        stressTimed = new Button(this);
        stressTimed.setText("开始定时压测");
        stressTimed.setOnClickListener(v -> startStress(true));
        root.addView(stressTimed);

        stressStop = new Button(this);
        stressStop.setText("停止压测并保留报告");
        stressStop.setVisibility(View.GONE);
        stressStop.setOnClickListener(v -> stopStress());
        root.addView(stressStop);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        thinkingToggle = new Button(this);
        thinkingToggle.setVisibility(View.GONE);
        thinkingToggle.setOnClickListener(v -> {
            boolean expand = thinkingContent.getVisibility() == View.GONE;
            thinkingContent.setVisibility(expand ? View.VISIBLE : View.GONE);
            thinkingToggle.setText(expand ? "思考过程（已展开，点击折叠）" : "思考过程（已折叠，点击展开）");
        });
        root.addView(thinkingToggle);

        thinkingContent = new TextView(this);
        thinkingContent.setTextIsSelectable(true);
        thinkingContent.setVisibility(View.GONE);
        root.addView(thinkingContent);

        result = new TextView(this);
        result.setTextIsSelectable(true);
        result.setPadding(0, 24, 0, 24);
        root.addView(result);

        Button copy = new Button(this);
        copy.setText("复制实测结果");
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("本地模型实测",
                    exportReport.isEmpty() ? result.getText() : exportReport));
            status.setText("结果已复制");
        });
        root.addView(copy);

        Button exportCsv = new Button(this);
        exportCsv.setText("导出采样 CSV");
        exportCsv.setOnClickListener(v -> exportStress(EXPORT_CSV));
        root.addView(exportCsv);

        Button exportZip = new Button(this);
        exportZip.setText("导出诊断包");
        exportZip.setOnClickListener(v -> exportStress(EXPORT_ZIP));
        root.addView(exportZip);
        refreshModelStatus();
    }

    private SharedPreferences preferences() {
        return getSharedPreferences("local_probe", MODE_PRIVATE);
    }

    private String modelName() {
        return preferences().getString("model_name", "model.gguf（原始文件名未记录）");
    }

    private static String modelVerification(String sha) {
        if (OFFICIAL_Q8_SHA256.equals(sha)) return "（官方 Q8_0 校验一致）";
        if (Q4_K_M_SHA256.equals(sha)) return "（unsloth Q4_K_M 校验一致）";
        return "（不是已登记的 Q8_0 / Q4_K_M，请核对来源）";
    }

    private static String quantization(String name) {
        String upper = name.toUpperCase(Locale.US);
        String[] values = {"Q4_K_M", "Q4_0", "Q5_K_M", "Q6_K", "Q8_0"};
        for (String value : values) if (upper.contains(value)) return value;
        return "未知";
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment() == null ? "model.gguf" : uri.getLastPathSegment();
    }

    private void selectModel() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/octet-stream");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_GGUF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXPORT_CSV || requestCode == EXPORT_ZIP) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) writePendingExport(data.getData());
            return;
        }
        if (requestCode != PICK_GGUF || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String sourceName = displayName(uri);
        worker.execute(() -> {
            File temporary = new File(getFilesDir(), "model.download");
            try (InputStream input = getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                try { nativeCloseModel(); } catch (Throwable ignored) { }
                if (!temporary.renameTo(modelFile)) throw new IllegalStateException("无法保存模型");
                String sha = sha256(modelFile);
                preferences().edit().putString("model_name", sourceName).putString("model_sha256", sha).apply();
                runOnUiThread(() -> status.setText(
                        "模型已导入：" + sourceName + "\n量化方式：" + quantization(sourceName)
                                + "\n大小 " + modelFile.length() + " 字节\nSHA256=" + sha
                                + modelVerification(sha)
                ));
            } catch (Exception error) {
                temporary.delete();
                runOnUiThread(() -> status.setText("模型导入失败：" + error.getMessage()));
            }
        });
    }

    private void refreshModelStatus() {
        String backend = BuildConfig.NATIVE_LIBRARY_NAME.contains("vulkan") ? "Vulkan" : "CPU";
        String vulkan = getPackageManager().hasSystemFeature("android.hardware.vulkan") ? "系统声明支持" : "系统未声明支持";
        String model = modelFile.isFile()
                ? "模型文件：" + modelName() + "\n量化方式：" + quantization(modelName()) + "\n模型大小 " + modelFile.length() + " 字节"
                : "尚未选择模型";
        status.setText("后端：" + backend + "（" + vulkan + "）\n" + model + "\n" + (loadError.isEmpty() ? "原生库加载成功" : loadError));
    }

    private void runGeneration() {
        if (stressActive) {
            status.setText("压测进行中，请先停止压测");
            return;
        }
        if (!modelFile.isFile()) {
            status.setText("请先选择 GGUF 模型");
            return;
        }
        if (!loadError.isEmpty()) {
            status.setText("原生库未加载：" + loadError);
            return;
        }
        String input = prompt.getText().toString().trim();
        if (input.isEmpty()) return;
        boolean thinking = deepThinking.isChecked();
        int tokenLimit = maxTokensValue();
        generate.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        thinkingToggle.setVisibility(View.GONE);
        thinkingContent.setVisibility(View.GONE);
        result.setText("正在加载并生成，请保持应用在前台…");
        worker.execute(() -> {
            AtomicBoolean sampling = new AtomicBoolean(true);
            final long[] peak = {Debug.getNativeHeapAllocatedSize()};
            Thread sampler = new Thread(() -> {
                while (sampling.get()) {
                    peak[0] = Math.max(peak[0], Debug.getNativeHeapAllocatedSize());
                    SystemClock.sleep(50);
                }
            });
            sampler.start();
            long start = SystemClock.elapsedRealtime();
            long cpuStart = readCpuJiffies();
            int tempStart = readBatteryTemperature();
            String raw;
            try {
                raw = nativeGenerate(modelFile.getAbsolutePath(), input, BuildConfig.NATIVE_LIBRARY_NAME, thinking, tokenLimit);
            } catch (Throwable error) {
                raw = "{\"error\":\"native call failed\"}";
            }
            long wall = SystemClock.elapsedRealtime() - start;
            long cpuDelta = readCpuJiffies() - cpuStart;
            int tempEnd = readBatteryTemperature();
            sampling.set(false);
            FormattedReport formatted = formatReport(raw, wall, cpuDelta, peak[0], tempStart, tempEnd, thinking);
            exportReport = formatted.exportText;
            saveReport(formatted.exportText);
            runOnUiThread(() -> {
                generate.setEnabled(true);
                progress.setVisibility(View.GONE);
                result.setText(formatted.displayText);
                showThinking(thinking, formatted.reasoning);
                refreshModelStatus();
            });
        });
    }

    private FormattedReport formatReport(String raw, long wallMs, long cpuDelta, long peakBytes,
                                         int tempStart, int tempEnd, boolean thinking) {
        StringBuilder metrics = new StringBuilder();
        metrics.append("版本：").append(BuildConfig.VERSION_NAME).append(" / ").append(BuildConfig.BUILD_TIME).append('\n');
        metrics.append("设备：").append(Build.MODEL).append('\n');
        metrics.append("系统：Android ").append(Build.VERSION.RELEASE).append(" / ").append(Build.VERSION.INCREMENTAL).append('\n');
        metrics.append("后端：").append(BuildConfig.NATIVE_LIBRARY_NAME).append('\n');
        metrics.append("模型文件：").append(modelName()).append('\n');
        metrics.append("量化方式：").append(quantization(modelName())).append('\n');
        metrics.append("深度思考：").append(thinking ? "开启" : "关闭").append('\n');
        String reasoning = "";
        String answer = raw;
        try {
            JSONObject json = new JSONObject(raw);
            if (json.has("error")) {
                metrics.append("错误：").append(json.optString("error")).append('\n');
                answer = "";
            } else {
                long modelLoad = json.optLong("modelLoadMs", -1);
                metrics.append("模型加载：").append(modelLoad).append(modelLoad == 0 ? " ms（复用会话）\n" : " ms\n");
                metrics.append("首 token 延迟：").append(json.optLong("firstTokenMs", -1)).append(" ms\n");
                metrics.append("思考耗时：").append(thinking ? json.optLong("thinkingMs", -1) + " ms" : "未开启").append('\n');
                metrics.append("正文生成耗时：").append(json.optLong("contentGenerationMs", -1)).append(" ms\n");
                metrics.append("生成速度：").append(json.optString("tokensPerSecond", "未知")).append(" token/s\n");
                metrics.append("生成 token 数：").append(json.optInt("generatedTokens", 0)).append('\n');
                int tokenLimit = json.optInt("maxNewTokens", -1);
                metrics.append("长度设置：").append(tokenLimit < 0 ? "不限" : tokenLimit).append('\n');
                String stop = json.optString("stopReason", "unknown");
                metrics.append("停止原因：").append(stopReasonLabel(stop)).append('\n');
                if (!"eog".equals(stop) && !"im_end".equals(stop)) {
                    metrics.append("注意：本次未自然结束，正文可能不完整\n");
                }
                ParsedReply parsed = parseReply(json.optString("output", ""), thinking);
                reasoning = parsed.reasoning;
                answer = parsed.content;
            }
        } catch (Exception error) {
            metrics.append("原生返回解析失败\n");
        }
        double cpuPercent = wallMs <= 0 ? 0 : cpuDelta * 1000.0 / wallMs;
        metrics.append("总耗时：").append(wallMs).append(" ms\n");
        metrics.append("采样 Native Heap 峰值：").append(String.format(Locale.US, "%.1f", peakBytes / 1024.0 / 1024.0)).append(" MiB\n");
        metrics.append("进程 CPU 估算：").append(String.format(Locale.US, "%.1f", cpuPercent)).append("%（单核口径，多线程可超过 100%）\n");
        metrics.append("电池温度：").append(tempStart / 10.0).append("℃ → ").append(tempEnd / 10.0).append("℃\n");
        String visibleAnswer = answer.isEmpty() ? "（本次未生成正文）" : answer;
        String export = metrics.toString();
        if (thinking && !reasoning.isEmpty()) export += "思考原文：\n" + reasoning + "\n\n";
        export += "回复：\n" + visibleAnswer;
        return new FormattedReport(metrics + "回复：\n" + visibleAnswer, export, reasoning);
    }

    private int maxTokensValue() {
        int position = maxTokens.getSelectedItemPosition();
        return position <= 0 ? -1 : Integer.parseInt(maxTokens.getSelectedItem().toString());
    }

    private static String stopReasonLabel(String reason) {
        if ("eog".equals(reason)) return "EOS（自然结束）";
        if ("im_end".equals(reason)) return "<|im_end|>（自然结束）";
        if ("max_tokens".equals(reason)) return "达到手动 token 上限";
        if ("context_full".equals(reason)) return "4096 上下文已满";
        if ("cancelled".equals(reason)) return "用户或定时器停止";
        return reason;
    }

    private void showThinking(boolean thinking, String reasoning) {
        if (!thinking || reasoning.isEmpty()) {
            thinkingToggle.setVisibility(View.GONE);
            thinkingContent.setVisibility(View.GONE);
            return;
        }
        thinkingContent.setText(reasoning);
        thinkingContent.setVisibility(View.GONE);
        thinkingToggle.setText("思考过程（已折叠，点击展开）");
        thinkingToggle.setVisibility(View.VISIBLE);
    }

    private void saveReport(String report) {
        try {
            File output = new File(getExternalFilesDir(null), "last_probe_result.txt");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                stream.write(report.getBytes("UTF-8"));
            }
        } catch (Exception ignored) { }
    }

    private static ParsedReply parseReply(String raw, boolean thinking) {
        String text = raw;
        int assistant = text.lastIndexOf("<|im_start|>assistant");
        if (assistant >= 0) text = text.substring(assistant + "<|im_start|>assistant".length());
        String[] endMarkers = {"</think>", "<｜end▁of▁thinking｜>", "<|end▁of▁thinking|>", "<|end_of_thinking|>"};
        int end = -1;
        String matchedEnd = "";
        for (String marker : endMarkers) {
            int index = text.indexOf(marker);
            if (index >= 0 && (end < 0 || index < end)) {
                end = index;
                matchedEnd = marker;
            }
        }
        int start = text.indexOf("<think>");
        int startLength = start >= 0 ? "<think>".length() : 0;
        String reasoning = "";
        String content;
        if (end >= 0) {
            int reasoningStart = start >= 0 && start < end ? start + startLength : 0;
            reasoning = text.substring(reasoningStart, end).trim();
            content = text.substring(end + matchedEnd.length());
        } else if (thinking || start >= 0) {
            reasoning = text.substring(start >= 0 ? start + startLength : 0).trim();
            content = "";
        } else {
            content = text;
        }
        content = content.replace("<|im_end|>", "").replace("<|endoftext|>", "").trim();
        return new ParsedReply(reasoning, content);
    }

    private static class ParsedReply {
        final String reasoning;
        final String content;
        ParsedReply(String reasoning, String content) {
            this.reasoning = reasoning;
            this.content = content;
        }
    }

    private static class FormattedReport {
        final String displayText;
        final String exportText;
        final String reasoning;
        FormattedReport(String displayText, String exportText, String reasoning) {
            this.displayText = displayText;
            this.exportText = exportText;
            this.reasoning = reasoning;
        }
    }

    private void startStress(boolean timed) {
        if (stressActive) return;
        if (!modelFile.isFile()) {
            status.setText("请先选择 GGUF 模型");
            return;
        }
        if (!loadError.isEmpty()) {
            status.setText("原生库未加载：" + loadError);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 45);
        }
        stressActive = true;
        boolean thinking = deepThinking.isChecked();
        int tokenLimit = maxTokensValue();
        long start = SystemClock.elapsedRealtime();
        long duration = timed ? stressDurationMs() : -1;
        stressMonitor = new StressMonitor(this, start, duration, MainActivity::nativeCancel);
        stressMonitor.start();
        stressCsv = "";
        exportReport = "";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            startForegroundService(new Intent(this, StressService.class));
        } catch (Exception error) {
            status.setText("前台服务启动失败，压测继续：" + error.getClass().getSimpleName());
        }
        generate.setEnabled(false);
        stressThree.setEnabled(false);
        stressTimed.setEnabled(false);
        stressStop.setVisibility(View.VISIBLE);
        thinkingToggle.setVisibility(View.GONE);
        thinkingContent.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        result.setText(timed ? "连续压测进行中，可随时停止…" : "连续 3 次压测进行中，可随时停止…");
        worker.execute(() -> {
            String error = "";
            int completed = 0;
            if (timed) {
                int index = 0;
                while (stressActive && (duration == 0 || SystemClock.elapsedRealtime() - start < duration)) {
                    long runStart = SystemClock.elapsedRealtime();
                    String raw = callStress(STRESS_PROMPTS[index % STRESS_PROMPTS.length], thinking, tokenLimit);
                    try {
                        JSONObject json = new JSONObject(raw);
                        if (json.has("error")) {
                            error = json.optString("error");
                            break;
                        }
                        stressMonitor.recordRun(json, runStart);
                        completed++;
                    } catch (Exception parseError) {
                        error = "原生结果解析失败";
                        break;
                    }
                    index++;
                }
            } else {
                for (int index = 0; index < 3 && stressActive; index++) {
                    long runStart = SystemClock.elapsedRealtime();
                    String raw = callStress(STRESS_PROMPTS[0], thinking, tokenLimit);
                    try {
                        JSONObject json = new JSONObject(raw);
                        if (json.has("error")) {
                            error = json.optString("error");
                            break;
                        }
                        stressMonitor.recordRun(json, runStart);
                        completed++;
                    } catch (Exception parseError) {
                        error = "原生结果解析失败";
                        break;
                    }
                }
            }
            stressActive = false;
            stressMonitor.finish();
            StringBuilder report = new StringBuilder(testHeader(timed ? "定时连续压测" : "连续 3 次压测", thinking, tokenLimit));
            report.append("实际完成任务：").append(completed).append('\n');
            if (!error.isEmpty()) report.append("错误：").append(error).append('\n');
            report.append(stressMonitor.buildReport());
            stressCsv = stressMonitor.toCsv();
            exportReport = report + "\n\n【采样 CSV】\n" + stressCsv;
            saveStressFiles(report.toString());
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                stressStop.setVisibility(View.GONE);
                generate.setEnabled(true);
                stressThree.setEnabled(true);
                stressTimed.setEnabled(true);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                stopStressService();
                result.setText(report.toString());
                status.setText("压测已结束，报告与 CSV 已保存");
            });
        });
    }

    private String callStress(String input, boolean thinking, int tokenLimit) {
        try {
            return nativeGenerate(modelFile.getAbsolutePath(), input, BuildConfig.NATIVE_LIBRARY_NAME, thinking, tokenLimit);
        } catch (Throwable error) {
            return "{\"error\":\"native call failed: " + error.getClass().getSimpleName() + "\"}";
        }
    }

    private long stressDurationMs() {
        int position = stressDuration.getSelectedItemPosition();
        if (position >= 4) return 0;
        int[] minutes = {1, 3, 5, 10};
        return minutes[position] * 60000L;
    }

    private String testHeader(String mode, boolean thinking, int tokenLimit) {
        return "版本：" + BuildConfig.VERSION_NAME + " / " + BuildConfig.BUILD_TIME + "\n"
                + "模式：" + mode + "\n"
                + "设备：" + Build.MODEL + " / Android " + Build.VERSION.RELEASE + " / " + Build.VERSION.INCREMENTAL + "\n"
                + "后端：" + BuildConfig.NATIVE_LIBRARY_NAME + "\n"
                + "模型文件：" + modelName() + "\n"
                + "量化方式：" + quantization(modelName()) + "\n"
                + "模型 SHA256：" + preferences().getString("model_sha256", "未记录") + "\n"
                + "深度思考：" + (thinking ? "开启" : "关闭") + "\n"
                + "长度设置：" + (tokenLimit < 0 ? "不限" : tokenLimit) + "\n"
                + "采样间隔：500 ms\n\n";
    }

    private void stopStress() {
        if (!stressActive) return;
        stressActive = false;
        nativeCancel();
        status.setText("正在停止当前生成并整理已采集数据…");
    }

    private void stopStressService() {
        try {
            Intent intent = new Intent(this, StressService.class);
            intent.setAction("stop");
            startService(intent);
        } catch (Exception ignored) { }
    }

    private void saveStressFiles(String report) {
        try {
            File directory = getExternalFilesDir(null);
            try (FileOutputStream output = new FileOutputStream(new File(directory, "last_stress_report.txt"))) {
                output.write(report.getBytes("UTF-8"));
            }
            stressMonitor.writeCsv(new File(directory, "last_stress_samples.csv"));
        } catch (Exception ignored) { }
    }

    private void exportStress(int requestCode) {
        if (stressCsv.isEmpty()) {
            status.setText("暂无压测采样数据");
            return;
        }
        if (requestCode == EXPORT_CSV) {
            pendingExportFile = new File(getExternalFilesDir(null), "last_stress_samples.csv");
        } else {
            try {
                pendingExportFile = createDiagnosticsZip();
            } catch (Exception error) {
                status.setText("诊断包生成失败：" + error.getMessage());
                return;
            }
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(requestCode == EXPORT_CSV ? "text/csv" : "application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, requestCode == EXPORT_CSV ? "sierxue-stress.csv" : "sierxue-stress-diagnostics.zip");
        startActivityForResult(intent, requestCode);
    }

    private File createDiagnosticsZip() throws Exception {
        File zip = new File(getExternalFilesDir(null), "stress_diagnostics.zip");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(zip))) {
            addZipEntry(output, "samples.csv", stressCsv.getBytes("UTF-8"));
            addZipEntry(output, "report.txt", result.getText().toString().getBytes("UTF-8"));
            addZipEntry(output, "device_info.txt", testHeader("诊断信息", deepThinking.isChecked(), maxTokensValue()).getBytes("UTF-8"));
            addZipEntry(output, "proc_meminfo_summary.txt", meminfoSummary().getBytes("UTF-8"));
        }
        return zip;
    }

    private static void addZipEntry(ZipOutputStream output, String name, byte[] data) throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(data);
        output.closeEntry();
    }

    private static String meminfoSummary() {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemTotal:") || line.startsWith("MemFree:") || line.startsWith("MemAvailable:")
                        || line.startsWith("Buffers:") || line.startsWith("Cached:")) text.append(line).append('\n');
            }
        } catch (Exception error) {
            return "不可用：" + error.getMessage();
        }
        return text.toString();
    }

    private void writePendingExport(Uri uri) {
        if (pendingExportFile == null) return;
        try (InputStream input = new FileInputStream(pendingExportFile);
             OutputStream output = getContentResolver().openOutputStream(uri)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            status.setText("导出完成");
        } catch (Exception error) {
            status.setText("导出失败：" + error.getMessage());
        }
    }

    private long readCpuJiffies() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/stat"))) {
            String[] parts = reader.readLine().split(" ");
            return Long.parseLong(parts[13]) + Long.parseLong(parts[14]);
        } catch (Exception error) {
            return 0;
        }
    }

    private int readBatteryTemperature() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        StringBuilder text = new StringBuilder();
        for (byte value : digest.digest()) text.append(String.format(Locale.US, "%02x", value));
        return text.toString();
    }

    @Override
    protected void onDestroy() {
        if (stressActive) stopStress();
        if (stressMonitor != null) stressMonitor.finish();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        stopStressService();
        try { nativeCancel(); } catch (Throwable ignored) { }
        worker.shutdownNow();
        super.onDestroy();
    }

    public static native String nativeGenerate(String modelPath, String prompt, String backend, boolean thinking, int maxNewTokens);
    private static native void nativeCancel();
    private static native void nativeCloseModel();
}
