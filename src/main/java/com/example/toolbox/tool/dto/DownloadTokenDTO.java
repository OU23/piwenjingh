package com.example.toolbox.tool.dto;

import lombok.Data;

/**
 * 换取下载令牌入参（契约 §9.7，可选 body）。
 */
@Data
public class DownloadTokenDTO {

    /** 不传 = 最新版 */
    private String versionId;
}
