package com.sierxue.localprobe;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;

final class StressMonitor extends Thread {
    private final Context context;
    private final long startElapsed;
    private final long durationMs;
    private final Runnable deadlineCallback;
    private final AtomicBoolean sampling = new AtomicBoolean(true);
    private final List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> tokenEvents = Collections.synchronizedList(new ArrayList<>());
    private final List<RunMetric> runs = Collections.synchronizedList(new ArrayList<>());
    private long previousCpuJiffies = Long.MIN_VALUE;
    private long previousCpuElapsed = -1;

    StressMonitor(Context context, long startElapsed, long durationMs, Runnable deadlineCallback) {
        super("stress-monitor");
        this.context = context.getApplicationContext();
        this.startElapsed = startElapsed;
        this.durationMs = durationMs;
        this.deadlineCallback = deadlineCallback;
    }

    @Override
    public void run() {
        try {
            while (sampling.get()) {
                sample();
                long elapsed = SystemClock.elapsedRealtime() - startElapsed;
                if (durationMs > 0 && elapsed >= durationMs) {
                    deadlineCallback.run();
                    break;
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            sample();
        }
    }

    void finish() {
        sampling.set(false);
        interrupt();
        try {
            join(1500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void sample() {
        long now = SystemClock.elapsedRealtime();
        Sample sample = new Sample();
        sample.elapsedMs = Math.max(0, now - startElapsed);
        sample.nativeAllocated = Debug.getNativeHeapAllocatedSize();
        sample.nativeSize = Debug.getNativeHeapSize();
        Runtime runtime = Runtime.getRuntime();
        sample.javaUsed = runtime.totalMemory() - runtime.freeMemory();
        readProcessMemory(sample);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            manager.getMemoryInfo(memory);
            sample.availableMemory = memory.availMem;
            sample.lowMemory = memory.lowMemory ? 1 : 0;
        }
        readBattery(sample);
        readCpu(sample, now);
        samples.add(sample);
    }

    private void readProcessMemory(Sample sample) {
        boolean rollupRead = false;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/smaps_rollup"))) {
            String line;
            double privateClean = 0;
            double privateDirty = 0;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Pss:")) {
                    sample.pss = parseKb(line) * 1024.0;
                    rollupRead = true;
                } else if (line.startsWith("Private_Clean:")) {
                    privateClean = parseKb(line) * 1024.0;
                } else if (line.startsWith("Private_Dirty:")) {
                    privateDirty = parseKb(line) * 1024.0;
                }
            }
            if (privateClean > 0 || privateDirty > 0) sample.uss = privateClean + privateDirty;
        } catch (Exception ignored) { }
        if (!rollupRead) {
            try {
                Debug.MemoryInfo info = new Debug.MemoryInfo();
                Debug.getMemoryInfo(info);
                int pss = info.getTotalPss();
                if (pss > 0) sample.pss = pss * 1024.0;
            } catch (Exception ignored) { }
        }
    }

    private static double parseKb(String line) {
        String[] parts = line.split("\\s+");
        return parts.length >= 2 ? Double.parseDouble(parts[1]) : Double.NaN;
    }

    private void readBattery(Sample sample) {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            if (battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
                sample.temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0;
            }
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) sample.batteryPercent = level * 100.0 / scale;
        }
        BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (manager != null) {
            int current = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (current != 0 && current != Integer.MIN_VALUE) {
                sample.currentUa = current;
                sample.currentFromManager = true;
            }
            long charge = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (charge > 0) sample.chargeUah = charge;
        }
        if (Double.isNaN(sample.currentUa)) {
            sample.currentUa = readNumber("/sys/class/power_supply/battery/current_now");
            if (!Double.isNaN(sample.currentUa)) sample.currentFromSysfs = true;
        }
        sample.voltageUv = readNumber("/sys/class/power_supply/battery/voltage_now");
    }

    private static double readNumber(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return Double.parseDouble(reader.readLine().trim());
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    private void readCpu(Sample sample, long now) {
        double jiffies = readNumber("/proc/self/stat", true);
        if (Double.isNaN(jiffies)) return;
        if (previousCpuJiffies != Long.MIN_VALUE && now > previousCpuElapsed) {
            sample.cpuPercent = (jiffies - previousCpuJiffies) * 1000.0 / (now - previousCpuElapsed);
        }
        previousCpuJiffies = (long) jiffies;
        previousCpuElapsed = now;
    }

    private static double readNumber(String path, boolean processStat) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String[] parts = reader.readLine().split(" ");
            return Double.parseDouble(parts[13]) + Double.parseDouble(parts[14]);
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    void recordRun(JSONObject json, long runStartElapsed) {
        RunMetric run = new RunMetric();
        run.index = runs.size() + 1;
        run.tokens = json.optInt("generatedTokens", 0);
        run.wallMs = SystemClock.elapsedRealtime() - runStartElapsed;
        run.firstTokenMs = json.optLong("firstTokenMs", -1);
        run.modelLoadMs = json.optLong("modelLoadMs", -1);
        run.stopReason = json.optString("stopReason", "unknown");
        try {
            run.tokensPerSecond = Double.parseDouble(json.optString("tokensPerSecond", "0"));
        } catch (NumberFormatException ignored) { }
        JSONArray times = json.optJSONArray("tokenTimesMs");
        if (times != null) {
            long offset = json.optLong("generationStartOffsetMs", 0);
            for (int i = 0; i < times.length(); i++) {
                tokenEvents.add(runStartElapsed - startElapsed + offset + times.optLong(i));
            }
        }
        runs.add(run);
    }

    String buildReport() {
        List<Sample> copy;
        List<Long> events;
        List<RunMetric> runCopy;
        synchronized (samples) { copy = new ArrayList<>(samples); }
        synchronized (tokenEvents) { events = new ArrayList<>(tokenEvents); }
        synchronized (runs) { runCopy = new ArrayList<>(runs); }
        StringBuilder text = new StringBuilder("【性能】\n");
        long elapsed = copy.isEmpty() ? 0 : copy.get(copy.size() - 1).elapsedMs;
        text.append("采样时长：").append(String.format(Locale.US, "%.1f 秒", elapsed / 1000.0)).append('\n');
        text.append("完成任务数：").append(runCopy.size()).append("，累计 token：").append(events.size()).append('\n');
        for (int i = 0; i < runCopy.size(); i++) {
            RunMetric run = runCopy.get(i);
            text.append("第 ").append(run.index).append(" 次：")
                    .append(run.tokens).append(" token，")
                    .append(String.format(Locale.US, "%.2f tok/s，首 token %d ms，总耗时 %d ms，加载 %d ms，停止 %s",
                            run.tokensPerSecond, run.firstTokenMs, run.wallMs, run.modelLoadMs, run.stopReason));
            if (i > 0 && runCopy.get(0).tokensPerSecond > 0) {
                double decay = (runCopy.get(0).tokensPerSecond - run.tokensPerSecond) * 100.0 / runCopy.get(0).tokensPerSecond;
                text.append("，速度相对第 1 次 ").append(String.format(Locale.US, "%+.1f%%", -decay));
            }
            text.append('\n');
        }
        text.append("\n【分钟分段】\n").append(minuteReport(copy, events));
        text.append("\n【内存】\n");
        text.append(metric(copy, "Native Heap 已分配", s -> s.nativeAllocated));
        text.append(metric(copy, "Native Heap Size", s -> s.nativeSize));
        text.append(metric(copy, "Java Heap 已用", s -> s.javaUsed));
        text.append(metric(copy, "进程 PSS", s -> s.pss));
        text.append(metric(copy, "进程 USS", s -> s.uss));
        text.append(metric(copy, "系统可用内存", s -> s.availableMemory));
        text.append("采样次数：").append(copy.size()).append("，完整时间序列见 CSV\n");
        text.append("\n【功耗】\n").append(powerReport(copy));
        text.append("\n【温度】\n").append(temperatureReport(copy));
        text.append("\n【自动结论】\n").append(conclusion(copy, events));
        return text.toString();
    }

    private static String metric(List<Sample> samples, String name, ToDoubleFunction<Sample> selector) {
        double peak = Double.NaN, sum = 0, first = Double.NaN, last = Double.NaN;
        int count = 0;
        for (Sample sample : samples) {
            double value = selector.applyAsDouble(sample);
            if (Double.isNaN(value)) continue;
            if (Double.isNaN(first)) first = value;
            last = value;
            peak = Double.isNaN(peak) ? value : Math.max(peak, value);
            sum += value;
            count++;
        }
        if (count == 0) return name + "：不可用（系统未提供、无权限或读取失败）\n";
        return String.format(Locale.US, "%s：峰值 %.1f MiB / 均值 %.1f MiB / 首尾差 %+.1f MiB\n",
                name, peak / 1048576.0, sum / count / 1048576.0, (last - first) / 1048576.0);
    }

    private String minuteReport(List<Sample> copy, List<Long> events) {
        if (copy.isEmpty()) return "数据不足以判断\n";
        long elapsed = copy.get(copy.size() - 1).elapsedMs;
        int buckets = Math.max(1, (int) (elapsed / 60000) + 1);
        double baseline = baselineSpeed(events, elapsed);
        StringBuilder text = new StringBuilder();
        if (baseline > 0) text.append(String.format(Locale.US, "前 30 秒基线：%.2f tok/s\n", baseline));
        int cumulativeTokens = 0;
        for (int bucket = 0; bucket < buckets; bucket++) {
            long from = bucket * 60000L;
            long to = Math.min(elapsed, from + 60000L);
            if (to <= from && bucket > 0) continue;
            int tokens = 0;
            for (long event : events) if (event >= from && event < from + 60000L) tokens++;
            cumulativeTokens += tokens;
            double seconds = Math.max(0.001, (to - from) / 1000.0);
            double speed = tokens / seconds;
            double tempSum = 0, tempMax = Double.NaN, nativeSum = 0, nativePeak = 0, cpuSum = 0;
            int tempCount = 0, cpuCount = 0, nativeCount = 0;
            for (Sample sample : copy) {
                if (sample.elapsedMs < from || sample.elapsedMs >= from + 60000L) continue;
                if (!Double.isNaN(sample.temperature)) {
                    tempSum += sample.temperature;
                    tempMax = Double.isNaN(tempMax) ? sample.temperature : Math.max(tempMax, sample.temperature);
                    tempCount++;
                }
                nativeSum += sample.nativeAllocated;
                nativePeak = Math.max(nativePeak, sample.nativeAllocated);
                nativeCount++;
                if (!Double.isNaN(sample.cpuPercent)) { cpuSum += sample.cpuPercent; cpuCount++; }
            }
            text.append(String.format(Locale.US,
                    "第 %d 分钟：%.2f tok/s，温度均值 %s / 最高 %s℃，Native 峰值 %.1f / 均值 %.1f MiB，CPU %s，累计 %d token",
                    bucket + 1, speed,
                    tempCount == 0 ? "不可用" : String.format(Locale.US, "%.1f", tempSum / tempCount),
                    Double.isNaN(tempMax) ? "不可用" : String.format(Locale.US, "%.1f", tempMax),
                    nativePeak / 1048576.0,
                    nativeSum / Math.max(1, nativeCount) / 1048576.0,
                    cpuCount == 0 ? "不可用" : String.format(Locale.US, "%.0f%%", cpuSum / cpuCount), cumulativeTokens));
            if (baseline > 0 && bucket > 0) {
                double decline = (baseline - speed) * 100.0 / baseline;
                text.append(String.format(Locale.US, "，相对基线 %+.1f%%", -decline));
                if (decline > 30) text.append("【疑似降频】");
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static double baselineSpeed(List<Long> events, long elapsed) {
        if (elapsed < 30000) return 0;
        int count = 0;
        for (long event : events) if (event >= 0 && event < 30000) count++;
        return count / 30.0;
    }

    private static String powerReport(List<Sample> copy) {
        double currentSum = 0, powerSum = 0, firstCharge = Double.NaN, lastCharge = Double.NaN;
        double firstPercent = Double.NaN, lastPercent = Double.NaN;
        int currentCount = 0, powerCount = 0;
        boolean managerCurrent = false, sysfsCurrent = false;
        for (Sample sample : copy) {
            if (!Double.isNaN(sample.currentUa)) {
                managerCurrent |= sample.currentFromManager;
                sysfsCurrent |= sample.currentFromSysfs;
                currentSum += Math.abs(sample.currentUa);
                currentCount++;
                if (!Double.isNaN(sample.voltageUv)) {
                    powerSum += Math.abs(sample.currentUa) * sample.voltageUv / 1.0e9;
                    powerCount++;
                }
            }
            if (!Double.isNaN(sample.chargeUah)) {
                if (Double.isNaN(firstCharge)) firstCharge = sample.chargeUah;
                lastCharge = sample.chargeUah;
            }
            if (!Double.isNaN(sample.batteryPercent)) {
                if (Double.isNaN(firstPercent)) firstPercent = sample.batteryPercent;
                lastPercent = sample.batteryPercent;
            }
        }
        StringBuilder text = new StringBuilder();
        if (currentCount > 0) {
            text.append("路径：").append(managerCurrent ? "BatteryManager 电流" : "sysfs 电流");
            if (managerCurrent && sysfsCurrent) text.append(" + sysfs 电流");
            text.append("（符号方向因机型而异，统计使用绝对值）\n");
            text.append(String.format(Locale.US, "平均电流：%.1f mA\n", currentSum / currentCount / 1000.0));
            if (powerCount > 0) text.append(String.format(Locale.US, "平均瞬时功率：%.2f W\n", powerSum / powerCount / 1000.0));
        } else if (!Double.isNaN(firstCharge) && lastCharge != firstCharge && !copy.isEmpty()) {
            double hours = copy.get(copy.size() - 1).elapsedMs / 3600000.0;
            text.append("路径：charge_counter 前后差值估算\n");
            if (hours > 0) text.append(String.format(Locale.US, "估算平均电流：%.1f mA\n",
                    Math.abs(lastCharge - firstCharge) / hours / 1000.0));
        } else {
            text.append("本机无法读取电流，功耗以温度与 CPU 推断（电流、电压、电荷计均不可用或无变化）\n");
        }
        if (!Double.isNaN(firstPercent)) {
            text.append(String.format(Locale.US, "电池电量：%.0f%% → %.0f%%\n", firstPercent, lastPercent));
        } else {
            text.append("电池电量：不可用（系统广播未提供）\n");
        }
        return text.toString();
    }

    private static String temperatureReport(List<Sample> copy) {
        double first = Double.NaN, last = Double.NaN, max = Double.NaN;
        long maxAt = -1;
        for (Sample sample : copy) {
            if (Double.isNaN(sample.temperature)) continue;
            if (Double.isNaN(first)) first = sample.temperature;
            last = sample.temperature;
            if (Double.isNaN(max) || sample.temperature > max) {
                max = sample.temperature;
                maxAt = sample.elapsedMs;
            }
        }
        if (Double.isNaN(first)) return "温度：不可用（系统电池广播未提供）\n";
        return String.format(Locale.US, "起始 %.1f℃，结束 %.1f℃，最高 %.1f℃（第 %.1f 秒）\n",
                first, last, max, maxAt / 1000.0);
    }

    private static String conclusion(List<Sample> copy, List<Long> events) {
        if (copy.isEmpty()) return "数据不足以判断";
        long elapsed = copy.get(copy.size() - 1).elapsedMs;
        if (elapsed <= 0 || events.size() < 2) return "数据不足以判断";
        double average = events.size() * 1000.0 / elapsed;
        double first = Double.NaN, last = Double.NaN, maxTemp = Double.NaN;
        int count = 0;
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (Sample sample : copy) {
            if (Double.isNaN(first)) first = sample.nativeAllocated;
            last = sample.nativeAllocated;
            if (!Double.isNaN(sample.temperature)) maxTemp = Double.isNaN(maxTemp) ? sample.temperature : Math.max(maxTemp, sample.temperature);
            double x = sample.elapsedMs / 60000.0;
            double y = sample.nativeAllocated / 1048576.0;
            sumX += x; sumY += y; sumXY += x * y; sumXX += x * x;
            count++;
        }
        double denominator = count * sumXX - sumX * sumX;
        double slope = count > 1 && Math.abs(denominator) > 0.0001 ? (count * sumXY - sumX * sumY) / denominator : 0;
        double baseline = baselineSpeed(events, elapsed);
        String throttle = "数据不足以判断";
        if (baseline > 0) {
            throttle = "未触发严重降频";
            int buckets = (int) (elapsed / 60000) + 1;
            for (int bucket = 1; bucket < buckets; bucket++) {
                int tokens = 0;
                for (long event : events) if (event >= bucket * 60000L && event < (bucket + 1) * 60000L) tokens++;
                long bucketStart = bucket * 60000L;
                double seconds = Math.max(0.001, (Math.min(elapsed, bucketStart + 60000L) - bucketStart) / 1000.0);
                double speed = tokens / seconds;
                double decline = (baseline - speed) * 100.0 / baseline;
                if (decline > 30) {
                    throttle = String.format(Locale.US, "第 %d 分钟起相对基线下降 %.1f%%，疑似降频", bucket + 1, decline);
                    break;
                }
            }
        }
        String memory = count < 10 ? "数据不足以判断" :
                (slope > 1 && last - first > 16 * 1048576 ? "存在持续增长迹象" : "无持续增长迹象");
        return String.format(Locale.US,
                "%.1f 分钟平均 %.2f tok/s，峰值温度 %s℃，%s；Native Heap 从 %.1f MiB 增至 %.1f MiB（斜率 %+.2f MiB/分钟），%s",
                elapsed / 60000.0, average,
                Double.isNaN(maxTemp) ? "不可用" : String.format(Locale.US, "%.1f", maxTemp),
                throttle, first / 1048576.0, last / 1048576.0, slope, memory);
    }

    String toCsv() {
        StringBuilder csv = new StringBuilder("elapsed_ms,native_allocated_bytes,native_size_bytes,java_used_bytes,pss_bytes,uss_bytes,avail_mem_bytes,low_memory,cpu_percent,temp_c,battery_percent,current_ua,charge_uah,voltage_uv\n");
        synchronized (samples) {
            for (Sample s : samples) {
                csv.append(s.elapsedMs).append(',')
                        .append(number(s.nativeAllocated)).append(',').append(number(s.nativeSize)).append(',')
                        .append(number(s.javaUsed)).append(',').append(number(s.pss)).append(',')
                        .append(number(s.uss)).append(',').append(number(s.availableMemory)).append(',')
                        .append(number(s.lowMemory)).append(',').append(number(s.cpuPercent)).append(',')
                        .append(number(s.temperature)).append(',').append(number(s.batteryPercent)).append(',')
                        .append(number(s.currentUa)).append(',').append(number(s.chargeUah)).append(',')
                        .append(number(s.voltageUv)).append('\n');
            }
        }
        return csv.toString();
    }

    void writeCsv(File file) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(toCsv().getBytes("UTF-8"));
        }
    }

    private static String number(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.US, "%.3f", value);
    }

    private static class Sample {
        long elapsedMs;
        double nativeAllocated = Double.NaN, nativeSize = Double.NaN, javaUsed = Double.NaN;
        double pss = Double.NaN, uss = Double.NaN, availableMemory = Double.NaN, lowMemory = Double.NaN;
        double cpuPercent = Double.NaN, temperature = Double.NaN, batteryPercent = Double.NaN;
        double currentUa = Double.NaN, chargeUah = Double.NaN, voltageUv = Double.NaN;
        boolean currentFromManager, currentFromSysfs;
    }

    private static class RunMetric {
        int index, tokens;
        long wallMs, firstTokenMs, modelLoadMs;
        double tokensPerSecond;
        String stopReason;
    }
}
