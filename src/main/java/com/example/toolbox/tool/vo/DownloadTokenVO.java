package com.example.toolbox.tool.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 短时效下载令牌出参（契约 §9.7 DownloadTokenVO）。
 */
@Data
@Builder
public class DownloadTokenVO {

    /** 短时效令牌，绑定「当前用户 + 工具 ID + 版本 ID」 */
    private String token;

    /** 有效期（秒），≤60 */
    private Long expiresIn;
}
