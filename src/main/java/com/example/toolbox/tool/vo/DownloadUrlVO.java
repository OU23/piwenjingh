package com.example.toolbox.tool.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 下载地址（契约 §9.5 附 / v1.19，M-22）。
 *
 * <p>由 {@code GET /api/tools/{id}/download-url} 返回：后端**先鉴权、后签发**，
 * 前端拿 `url` 直接导航下载。
 */
@Data
@Builder
public class DownloadUrlVO {

    /** 可直接访问的短时效地址：{@code direct=true} 为 OSS 预签名 URL；{@code false} 为后端令牌下载地址 */
    private String url;

    /** 有效期（秒） */
    private long expiresIn;

    /** true = 浏览器**直连 OSS**（字节不过后端）；false = 由**后端代理**返回（本地文件） */
    private boolean direct;
}
