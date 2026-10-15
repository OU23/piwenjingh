package com.example.toolbox.upload.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 直传临时凭证（契约 v1.20 §8.6，M-23）。
 *
 * <p><b>受控例外</b>：这是全项目**唯一**允许把"写权限"交给前端的出口（契约 §9.5 / 方案 §2.3）。
 * 凭证**短时效 + 限前缀（本部门）+ 限动作（仅上传）**，由后端鉴权后签发。
 *
 * <p>⚠️ 本对象**含密钥字段**，**禁止写入日志**（含访问日志、异常栈）。
 */
@Data
@Builder
public class UploadCredentialVO {

    /** 对象键（= 最终 fileKey），形如 {@code tools/<deptId>/<uuid><ext>}，**由后端生成** */
    private String objectKey;

    /** OSS 区域（如 {@code oss-cn-hangzhou}），供 SDK 构造 */
    private String region;

    /** OSS **公网** endpoint */
    private String endpoint;

    /** bucket 名 */
    private String bucket;

    /** 临时凭证 */
    private Credentials credentials;

    /** 凭证过期时间（ISO-8601，如 {@code 2026-10-12T09:30:00Z}） */
    private String expiration;

    /** **实际生效**的分片大小（字节）；★ 可能被自动上调（片数 > 10000 时），前端须按此值切分 */
    private Long partSize;

    /** OSS 分片数上限（固定 10000） */
    private Integer maxParts;

    /** 临时凭证三元组（STS）。**禁止入日志**。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Credentials {
        private String accessKeyId;
        private String accessKeySecret;
        private String securityToken;
    }
}
