package com.fyt.updater;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** GitHub Release 检查、私有缓存下载、APK 校验与安装。 */
public final class GhUpdater {
    private static final String DEFAULT_API_BASE = "https://api.github.com/repos/";
    private static final int TIMEOUT_MS = 10_000;

    private final UpdaterConfig config;
    private final Handler mainHandler;
    private ReleaseInfo latestRelease;
    private UpdateCallback callback = new UpdateCallback() {
        @Override public void onUpdateAvailable(ReleaseInfo release) { }
    };
    private volatile HttpURLConnection activeConnection;
    private volatile boolean cancelRequested;
    private String lastError = "检查失败（网络不通或仓库无 Release）";

    public GhUpdater(UpdaterConfig config) {
        this.config = config;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void checkUpdate(final UpdateCallback callback) {
        this.callback = callback;
        new Thread(() -> {
            final ReleaseInfo release = checkLatestSync();
            mainHandler.post(() -> {
                if (release == null) {
                    callback.onError(lastError);
                    return;
                }
                latestRelease = release;
                if (isNewer(release)) callback.onUpdateAvailable(release);
                else callback.onUpToDate();
            });
        }, "GhUpdaterCheck").start();
    }

    public ReleaseInfo checkLatestSync() {
        HttpURLConnection connection = null;
        try {
            String base = config.baseUrl == null || config.baseUrl.isEmpty()
                    ? DEFAULT_API_BASE : config.baseUrl;
            if (!base.endsWith("/")) base += "/";
            connection = (HttpURLConnection) new URL(base + config.repo + "/releases/latest").openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", config.appName.isEmpty() ? "SiErXue" : config.appName);
            int code = connection.getResponseCode();
            if (code != 200) {
                lastError = "检查失败（HTTP " + code + "）";
                Log.w(config.logTag, lastError);
                return null;
            }
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) text.append(line);
            }
            JSONObject root = new JSONObject(text.toString());
            String tag = root.optString("tag_name", "");
            String body = root.optString("body", "");
            int versionCode = parseTagVersion(tag);
            AssetSelection selection = selectAsset(root.optJSONArray("assets"), tag);
            if (selection == null) {
                lastError = "Release 没有可下载的 APK";
                return null;
            }
            return new ReleaseInfo(tag, body, selection.url, versionCode, selection.size, selection.name);
        } catch (Throwable error) {
            lastError = "检查失败：" + error.getClass().getSimpleName();
            Log.w(config.logTag, "检查更新失败", error);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private AssetSelection selectAsset(JSONArray assets, String tag) {
        if (assets == null) return null;
        AssetSelection first = null;
        AssetSelection appMatch = null;
        AssetSelection versionMatch = null;
        String version = tag == null ? "" : tag.replaceFirst("^v", "");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            if (!name.endsWith(".apk")) continue;
            AssetSelection candidate = new AssetSelection(
                    name, asset.optString("browser_download_url", ""), asset.optLong("size", 0));
            if (!candidate.url.isEmpty() && first == null) first = candidate;
            boolean nameMatches = config.appName.isEmpty() || name.startsWith(config.appName);
            if (!candidate.url.isEmpty() && nameMatches && appMatch == null) appMatch = candidate;
            if (!candidate.url.isEmpty() && !version.isEmpty() && name.contains(version) && versionMatch == null) {
                versionMatch = candidate;
            }
        }
        if (versionMatch != null) return versionMatch;
        if (appMatch != null) return appMatch;
        return first;
    }

    public boolean isNewer(ReleaseInfo release) {
        return release != null && release.hasDownload() && release.versionCode > config.currentVersionCode;
    }

    /** 异步下载到应用私有缓存，完成后校验并打开系统安装页。 */
    public long downloadAndInstall(final Context context) {
        if (latestRelease == null || !latestRelease.hasDownload()) return -1;
        return downloadAndInstall(context, latestRelease.downloadUrl);
    }

    public long downloadAndInstall(final Context context, final String downloadUrl) {
        if (latestRelease == null) return -1;
        cancelRequested = false;
        new Thread(() -> performDownload(context, downloadUrl), "GhUpdaterDownload").start();
        return 1;
    }

    private void performDownload(Context context, String downloadUrl) {
        File target = null;
        File partial = null;
        try {
            File directory = new File(context.getCacheDir(), "updates");
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("无法创建更新缓存目录");
            String safeName = latestRelease.assetName == null || latestRelease.assetName.isEmpty()
                    ? "update.apk" : latestRelease.assetName.replaceAll("[^A-Za-z0-9._-]", "_");
            target = new File(directory, safeName);
            partial = new File(directory, safeName + ".download");
            if (partial.exists()) partial.delete();

            activeConnection = (HttpURLConnection) new URL(downloadUrl).openConnection();
            activeConnection.setConnectTimeout(TIMEOUT_MS);
            activeConnection.setReadTimeout(TIMEOUT_MS);
            activeConnection.setRequestProperty("User-Agent", config.appName.isEmpty() ? "SiErXue" : config.appName);
            int code = activeConnection.getResponseCode();
            if (code != 200) throw new IllegalStateException("下载失败（HTTP " + code + "）");
            long total = activeConnection.getContentLengthLong();
            if (total <= 0) total = latestRelease.downloadSize;
            long downloaded = 0;
            long lastReported = -1;
            try (InputStream input = activeConnection.getInputStream();
                 FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[32 * 1024];
                while (true) {
                    if (cancelRequested) throw new DownloadCancelledException();
                    int count = input.read(buffer);
                    if (count < 0) break;
                    output.write(buffer, 0, count);
                    downloaded += count;
                    if (lastReported < 0 || downloaded - lastReported >= 256 * 1024 || downloaded == total) {
                        lastReported = downloaded;
                        postProgress(downloaded, total);
                    }
                }
                output.getFD().sync();
            }
            if (downloaded == 0) throw new IllegalStateException("下载文件为空");
            if (!partial.renameTo(target)) {
                try (FileInputStream input = new FileInputStream(partial);
                     FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                }
                partial.delete();
            }
            String validation = validateApk(context, target);
            if (validation != null) {
                target.delete();
                throw new IllegalStateException(validation);
            }
            final File validApk = target;
            mainHandler.post(() -> {
                if (installApk(context, validApk)) callback.onDownloadComplete();
            });
        } catch (DownloadCancelledException cancelled) {
            if (partial != null) partial.delete();
            postError("下载已取消");
        } catch (Throwable error) {
            if (partial != null) partial.delete();
            if (cancelRequested) postError("下载已取消");
            else {
                Log.w(config.logTag, "下载或校验失败", error);
                postError(error.getMessage() == null ? "下载或校验失败" : error.getMessage());
            }
        } finally {
            if (activeConnection != null) activeConnection.disconnect();
            activeConnection = null;
        }
    }

    /** 返回 null 表示 APK 合法，否则返回用户可读错误。 */
    public String validateApk(Context context, File apk) {
        try {
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (info == null) return "下载文件不是合法 APK";
            if (!config.expectedPackageName.isEmpty() && !config.expectedPackageName.equals(info.packageName)) {
                return "APK 包名不匹配，已丢弃";
            }
            if (info.versionCode <= config.currentVersionCode) return "APK 版本不高于当前版本，已丢弃";
            return null;
        } catch (Throwable error) {
            return "APK 校验失败";
        }
    }

    public void cancelDownload() {
        cancelRequested = true;
        if (activeConnection != null) activeConnection.disconnect();
    }

    private boolean installApk(Context context, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !context.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                callback.onError("请在系统设置中允许安装未知应用，然后重新点击更新");
            } catch (Throwable error) {
                callback.onError("未授予安装未知应用权限，且无法打开系统设置");
            }
            return false;
        }
        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            return true;
        } catch (Throwable error) {
            callback.onError("无法打开 APK 安装页面");
            return false;
        }
    }

    private void postProgress(long downloaded, long total) {
        mainHandler.post(() -> callback.onDownloadProgress(downloaded, total));
    }

    private void postError(String message) {
        mainHandler.post(() -> callback.onError(message));
    }

    public ReleaseInfo getLatestRelease() { return latestRelease; }

    public static int parseTagVersion(String tag) {
        try {
            String value = tag.replaceAll("[^0-9.]", "");
            String[] parts = value.split("\\.");
            int code = 0;
            int[] weights = {10000, 100, 1};
            for (int i = 0; i < parts.length && i < weights.length; i++) {
                code += Integer.parseInt(parts[i]) * weights[i];
            }
            return code;
        } catch (Throwable error) {
            return 0;
        }
    }

    private static final class AssetSelection {
        final String name;
        final String url;
        final long size;
        AssetSelection(String name, String url, long size) { this.name = name; this.url = url; this.size = size; }
    }

    private static final class DownloadCancelledException extends Exception { }
}
