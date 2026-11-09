package com.fyt.updater;

/** 所有回调均回到 Android 主线程。 */
public interface UpdateCallback {
    void onUpdateAvailable(ReleaseInfo release);
    default void onUpToDate() {}
    default void onError(String message) {}
    default void onDownloadProgress(long downloadedBytes, long totalBytes) {}
    default void onDownloadComplete() {}
}
