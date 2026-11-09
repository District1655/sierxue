package com.fyt.updater;

/** GitHub Release 与选中 APK 资产的信息。 */
public final class ReleaseInfo {
    public final String tagName;
    public final String body;
    public final String downloadUrl;
    public final int versionCode;
    public final long downloadSize;
    public final String assetName;

    ReleaseInfo(String tag, String body, String url, int code, long size, String assetName) {
        this.tagName = tag;
        this.body = body;
        this.downloadUrl = url;
        this.versionCode = code;
        this.downloadSize = size;
        this.assetName = assetName;
    }

    public boolean hasDownload() { return downloadUrl != null && !downloadUrl.isEmpty(); }
}
