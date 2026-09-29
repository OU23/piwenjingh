package com.example.toolbox.tool.service;

/**
 * 单段 {@code Range} 的解析结果（契约 §9.5）。
 *
 * <p>只承载"区间"本身：起点与终点（**均含**）。"是否 206""是否计数"属调用方的语义，
 * 不放这里 —— Range 解析是**纯函数**，与鉴权、版本定位、计数口径无关，故独立成类。
 */
public record RangeSpec(long start, long end) {

    /**
     * 解析 {@code Range: bytes=<start>-<end>?}（**单段**）。
     *
     * <p>返回 {@code null} 表示"忽略该 Range"，调用方按完整下载（200）响应。以下情形一律忽略：
     * <ul>
     *   <li>空值 / 非 {@code bytes=} 前缀 / 无连字符；</li>
     *   <li>多段（含逗号）、后缀形式 {@code bytes=-N} —— 契约未要求，不支持；</li>
     *   <li>数字解析失败、起点为负、**区间倒置**（end &lt; start）；</li>
     *   <li>起点 &ge; 文件大小（越界）：选择忽略而非 416，让客户端可自然降级为整体下载。</li>
     * </ul>
     * 终点越界（&ge; size）按 RFC 9110 **收敛到末字节**，属合法请求而非忽略。
     *
     * @param rangeHeader 请求头原值，可为 null
     * @param size        文件总大小（字节）
     */
    public static RangeSpec parse(String rangeHeader, long size) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return null;
        }
        String value = rangeHeader.trim();
        if (!value.startsWith("bytes=")) {
            return null;
        }
        String spec = value.substring("bytes=".length()).trim();
        if (spec.isEmpty() || spec.contains(",")) {
            return null;
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startPart = spec.substring(0, dash).trim();
        String endPart = spec.substring(dash + 1).trim();
        if (startPart.isEmpty()) {
            // 后缀形式（bytes=-N）不支持 → 忽略
            return null;
        }
        long start;
        try {
            start = Long.parseLong(startPart);
        } catch (NumberFormatException e) {
            return null;
        }
        if (start < 0 || start >= size) {
            return null;
        }
        long end = size - 1;
        if (!endPart.isEmpty()) {
            long parsedEnd;
            try {
                parsedEnd = Long.parseLong(endPart);
            } catch (NumberFormatException e) {
                return null;
            }
            if (parsedEnd < start) {
                // 区间倒置（如 bytes=80-20）→ 非法，忽略
                return null;
            }
            // 终点越界 → 收敛到末字节（RFC 9110）
            end = Math.min(parsedEnd, size - 1);
        }
        return new RangeSpec(start, end);
    }
}
