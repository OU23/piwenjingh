package com.example.toolbox.common.constant;

/**
 * 全局设置键（tb_setting.setting_key）。禁止裸字符串散落逻辑（principles.md 原则 9）。
 */
public final class SettingKey {

    /** 存储根路径 */
    public static final String STORAGE_ROOT = "storageRoot";

    /** 单文件大小上限（字节） */
    public static final String MAX_FILE_SIZE = "maxFileSize";

    /** 上传分片大小（字节，M-23 / B14）；`local` 模式即切片大小，`oss` 直传即 OSS part 大小 */
    public static final String CHUNK_SIZE = "chunkSize";

    private SettingKey() {
    }
}
