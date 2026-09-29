package com.example.toolbox.common.util;

/**
 * 路径字符串处理工具。
 *
 * <p>存在的理由：契约 §十（v1.5）明确 `storageRoot` **统一使用正斜杠 `/`** ——
 * Windows 也写 `D:/toolbox/files`，不写 `D:\toolbox\files`。
 * 前端保存前已归一化，后端**再做一次容错归一化**，保证任何客户端（或直接写库的脚本）
 * 落库的都是统一格式；否则同一个目录会出现两种写法，排查问题时极易误判为"两个不同路径"。
 */
public final class PathUtils {

    /**
     * 归一化为正斜杠路径：`\` → `/`，去首尾空白，去尾部多余 `/`。
     *
     * <p>保留盘符根与 Unix 根：`D:/` 仍是 `D:/`（不裁成 `D:`，后者在 Windows 上表示"当前盘目录"，
     * 语义完全不同）；`/` 仍是 `/`。
     *
     * @return 归一化后的字符串；入参为 null → null；空串 / 纯空白 → 空串
     */
    public static String normalizeSlashes(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().replace('\\', '/');
        while (value.length() > 1 && value.endsWith("/")) {
            String head = value.substring(0, value.length() - 1);
            if (head.endsWith(":")) {
                break;   // 盘符根（D:/）保留斜杠
            }
            value = head;
        }
        return value;
    }

    private PathUtils() {
    }
}
