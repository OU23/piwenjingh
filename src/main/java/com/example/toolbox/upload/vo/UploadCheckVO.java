package com.example.toolbox.upload.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 上传前检查出参（契约 §8.1 UploadCheckVO）。
 */
@Data
@Builder
public class UploadCheckVO {

    /** **已成功接收**的分片序号（从 1 开始，升序）；无则为空数组（非 null） */
    private List<Integer> uploadedChunks;
}
