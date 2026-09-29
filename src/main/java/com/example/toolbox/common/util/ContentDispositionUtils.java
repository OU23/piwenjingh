package com.example.toolbox.common.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * {@code Content-Disposition} 构造（契约 §9.5 / §6.2）：
 * **ASCII 兜底名 + UTF-8 编码名双写**，保证中文文件名在浏览器保存时不错码。
 *
 * <p>形如：{@code attachment; filename="tool.zip"; filename*=UTF-8''%E5%B7%A5%E5%85%B7.zip}
 *
 * <p>注：下载链路（{@code DownloadController}）内有一份等价实现；本阶段**未改动已验收的下载链路**，
 * 后续可统一收敛到本工具类。
 */
public final class ContentDispositionUtils {

    /** 构造 attachment 头值；文件名为空时回退为 {@code download} */
    public static String attachment(String fileName) {
        String name = (fileName == null || fileName.isBlank()) ? "download" : fileName;
        // ASCII 兜底名：非 ASCII 字符替换为下划线，避免 header 编码异常
        String ascii = name.replaceAll("[^\\x20-\\x7e]", "_").replace("\"", "'");
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    private ContentDispositionUtils() {
    }
}
