package com.sierxue.localprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int PICK_GGUF = 42;
    private static final String OFFICIAL_Q8_SHA256 = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView result;
    private EditText prompt;
    private ProgressBar progress;
    private Button generate;
    private File modelFile;
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
        title.setText("Qwen3 本地模型可行性验证\n" + Build.MODEL + " / Android " + Build.VERSION.RELEASE);
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

        generate = new Button(this);
        generate.setText("加载模型并生成");
        generate.setOnClickListener(v -> runGeneration());
        root.addView(generate);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        result = new TextView(this);
        result.setTextIsSelectable(true);
        result.setPadding(0, 24, 0, 24);
        root.addView(result);

        Button copy = new Button(this);
        copy.setText("复制实测结果");
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("本地模型实测", result.getText()));
            status.setText("结果已复制");
        });
        root.addView(copy);
        refreshModelStatus();
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
        if (requestCode != PICK_GGUF || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        worker.execute(() -> {
            File temporary = new File(getFilesDir(), "model.download");
            try (InputStream input = getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                if (!temporary.renameTo(modelFile)) throw new IllegalStateException("无法保存模型");
                String sha = sha256(modelFile);
                runOnUiThread(() -> status.setText(
                        "模型已导入，大小 " + modelFile.length() + " 字节\nSHA256=" + sha
                                + (OFFICIAL_Q8_SHA256.equals(sha) ? "（官方 Q8 校验一致）" : "（与官方 Q8 不一致，请确认文件）")
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
        String model = modelFile.isFile() ? "模型大小 " + modelFile.length() + " 字节" : "尚未选择模型";
        status.setText("后端：" + backend + "（" + vulkan + "）\n" + model + "\n" + (loadError.isEmpty() ? "原生库加载成功" : loadError));
    }

    private void runGeneration() {
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
        generate.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
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
                raw = nativeGenerate(modelFile.getAbsolutePath(), input, BuildConfig.NATIVE_LIBRARY_NAME);
            } catch (Throwable error) {
                raw = "{\"error\":\"native call failed\"}";
            }
            long wall = SystemClock.elapsedRealtime() - start;
            long cpuDelta = readCpuJiffies() - cpuStart;
            int tempEnd = readBatteryTemperature();
            sampling.set(false);
            String report = formatReport(raw, wall, cpuDelta, peak[0], tempStart, tempEnd);
            try {
                File output = new File(getExternalFilesDir(null), "last_probe_result.txt");
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    stream.write(report.getBytes("UTF-8"));
                }
            } catch (Exception ignored) { }
            runOnUiThread(() -> {
                generate.setEnabled(true);
                progress.setVisibility(View.GONE);
                result.setText(report);
                refreshModelStatus();
            });
        });
    }

    private String formatReport(String raw, long wallMs, long cpuDelta, long peakBytes, int tempStart, int tempEnd) {
        StringBuilder report = new StringBuilder();
        report.append("设备：").append(Build.MODEL).append('\n');
        report.append("系统：Android ").append(Build.VERSION.RELEASE).append(" / ").append(Build.VERSION.INCREMENTAL).append('\n');
        report.append("后端：").append(BuildConfig.NATIVE_LIBRARY_NAME).append('\n');
        try {
            JSONObject json = new JSONObject(raw);
            if (json.has("error")) {
                report.append("错误：").append(json.optString("error"));
            } else {
                report.append("模型加载：").append(json.optLong("modelLoadMs", -1)).append(" ms\n");
                report.append("首 token 延迟：").append(json.optLong("firstTokenMs", -1)).append(" ms\n");
                report.append("生成速度：").append(json.optString("tokensPerSecond", "未知")).append(" token/s\n");
                report.append("生成 token 数：").append(json.optInt("generatedTokens", 0)).append('\n');
                report.append("回复：\n").append(json.optString("output", ""));
            }
        } catch (Exception error) {
            report.append("原生返回：").append(raw);
        }
        double cpuPercent = wallMs <= 0 ? 0 : cpuDelta * 1000.0 / wallMs;
        report.append("\n总耗时：").append(wallMs).append(" ms\n");
        report.append("采样 Native Heap 峰值：").append(String.format(Locale.US, "%.1f", peakBytes / 1024.0 / 1024.0)).append(" MiB\n");
        report.append("进程 CPU 估算：").append(String.format(Locale.US, "%.1f", cpuPercent)).append("%（单核口径，多线程可超过 100%）\n");
        report.append("电池温度：").append(tempStart / 10.0).append("℃ → ").append(tempEnd / 10.0).append("℃");
        return report.toString();
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
        worker.shutdownNow();
        super.onDestroy();
    }

    public static native String nativeGenerate(String modelPath, String prompt, String backend);
}
