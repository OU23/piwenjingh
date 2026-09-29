package com.example.toolbox.upload.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 合并分片出参（契约 §8.1 UploadMergeVO）。
 * fileKey 为**文件标识**（存储键），后续创建工具 / 版本时回传；**不体现物理路径**。
 */
@Data
@Builder
public class UploadMergeVO {

    private String fileKey;

    private String fileName;

    private Long fileSize;
}
