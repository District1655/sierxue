package com.fyt.updater;

/** 通用 GitHub Release 更新配置。 */
public final class UpdaterConfig {
    public final String repo;
    public final String appName;
    public final int currentVersionCode;
    public final String currentVersionName;
    public final String logTag;
    /** 可选 API 前缀，默认空字符串使用官方 GitHub API。 */
    public final String baseUrl;
    public final String expectedPackageName;

    private UpdaterConfig(Builder b) {
        this.repo = b.repo;
        this.appName = b.appName;
        this.currentVersionCode = b.currentVersionCode;
        this.currentVersionName = b.currentVersionName;
        this.logTag = b.logTag;
        this.baseUrl = b.baseUrl;
        this.expectedPackageName = b.expectedPackageName;
    }

    public static final class Builder {
        private final String repo;
        private String appName = "";
        private int currentVersionCode = 0;
        private String currentVersionName = "";
        private String logTag = "GhUpdater";
        private String baseUrl = "";
        private String expectedPackageName = "";

        public Builder(String repo) {
            if (repo == null || !repo.contains("/")) throw new IllegalArgumentException("repo must be 'owner/repo'");
            this.repo = repo;
        }

        public Builder appName(String value) { this.appName = value == null ? "" : value; return this; }
        public Builder currentVersion(int code, String name) { this.currentVersionCode = code; this.currentVersionName = name == null ? "" : name; return this; }
        public Builder logTag(String value) { this.logTag = value == null ? "GhUpdater" : value; return this; }
        public Builder baseUrl(String value) { this.baseUrl = value == null ? "" : value; return this; }
        public Builder expectedPackageName(String value) { this.expectedPackageName = value == null ? "" : value; return this; }
        public UpdaterConfig build() { return new UpdaterConfig(this); }
    }
}
