package com.example.toolbox.storage;

/**
 * 文件落点（M-22 / 数据模型 v1.2 §5.5，对应 {@code tb_tool_version.storage_backend}）。
 *
 * <p>取值与数据库一致：{@code local} / {@code oss}。用于下载分流与删除路由 ——
 * **对某文件的读写，只应作用于它实际所在的那一个后端**（不做跨后端连带操作）。
 */
public enum StorageBackend {

    /** 本机磁盘 */
    LOCAL("local"),

    /** 阿里云 OSS */
    OSS("oss");

    private final String value;

    StorageBackend(String value) {
        this.value = value;
    }

    /** 落库值（与 DB 一致，小写） */
    public String value() {
        return value;
    }

    /** 解析落库值；未知 / 空 一律按 {@link #LOCAL}（兼容存量数据）。 */
    public static StorageBackend of(String value) {
        if (value == null) {
            return LOCAL;
        }
        String v = value.trim();
        for (StorageBackend b : values()) {
            if (b.value.equalsIgnoreCase(v)) {
                return b;
            }
        }
        return LOCAL;
    }
}
