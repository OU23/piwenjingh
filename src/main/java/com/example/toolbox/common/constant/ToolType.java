package com.example.toolbox.common.constant;

/**
 * 工具类型（M-25 / 契约 v1.23 §6.3、数据模型 v1.6 §3.5 `tb_tool.tool_type`）。
 *
 * <p>两类工具共用**同一套**可见性 / 部门 / 定向授权判定，仅在「有无文件 / 版本」上分叉：
 * <ul>
 *   <li>{@link #FILE}：文件工具，必须有 {@code tb_tool_version} 记录；</li>
 *   <li>{@link #LINK}：链接工具，**无版本记录**（{@code latest_version_id = 0}、
 *       {@code latest_version = ''}、{@code file_size = 0}、{@code file_ext = ''}）。</li>
 * </ul>
 *
 * <p>{@link #ALL} 只用于列表筛选参数，**不落库**。
 */
public final class ToolType {

    /** 文件工具（存量数据默认值） */
    public static final String FILE = "FILE";

    /** 链接工具 */
    public static final String LINK = "LINK";

    /** 列表筛选「全部」（仅作查询参数，不落库） */
    public static final String ALL = "ALL";

    /**
     * 落库值归一化：仅 {@code LINK}（大小写不敏感）视为链接工具，其余一律按文件工具处理。
     * 老数据 / 未赋值时返回 {@code FILE}，保证「存量零回填」的语义在应用层同样成立。
     */
    public static String of(String raw) {
        return isLink(raw) ? LINK : FILE;
    }

    /** 是否链接工具（{@code null} / 空串 / 非法值一律视为文件工具） */
    public static boolean isLink(String raw) {
        return raw != null && LINK.equalsIgnoreCase(raw.trim());
    }

    /**
     * 列表筛选参数解析：空值 → {@link #ALL}；{@code ALL / FILE / LINK}（大小写不敏感）→ 大写值；
     * 其他 → {@code null}（由调用方转 {@code 1003}）。
     */
    public static String parseFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        String v = raw.trim().toUpperCase();
        return (ALL.equals(v) || FILE.equals(v) || LINK.equals(v)) ? v : null;
    }

    private ToolType() {
    }
}
