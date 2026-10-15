package com.example.toolbox.upload.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 上传模式与分片参数（契约 v1.20 §8.5，M-23）。
 *
 * <p>前端**进入上传页第一步**就调用 `/api/upload/mode`，据此决定走直传还是原链路 ——
 * 不允许"先试直传、失败再回退"（会产生半截状态）。同时它把**分片参数与上限的唯一来源**
 * 收敛到后端，前端不得写死。
 */
@Data
@Builder
public class UploadModeVO {

    /** {@code direct}（直传 OSS）/ {@code server}（经服务器中转）。取自全局开关 toolbox.storage.type */
    private String mode;

    /** 生效的分片大小（字节）；取自全局设置 chunkSize（缺省 / 非法回落 20 MB） */
    private Long partSize;

    /** 分片大小允许下限（字节）：5 MB */
    private Long partSizeMin;

    /** 分片大小允许上限（字节）：100 MB */
    private Long partSizeMax;

    /** 单文件上限（字节）；取自全局设置 maxFileSize（默认 10 GB） */
    private Long maxFileSize;
}
