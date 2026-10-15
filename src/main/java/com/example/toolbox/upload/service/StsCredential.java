package com.example.toolbox.upload.service;

/**
 * STS 临时凭证（M-23 / B14）。
 *
 * <p><b>敏感对象</b>：`accessKeySecret` / `securityToken` **禁止写入任何日志**
 * （security.md；与 B13「签名 URL 不写日志」同款要求）。故本记录**不提供 toString**
 * 之外的便利方法，且调用方不得将其整体打印。
 *
 * @param accessKeyId     临时 AccessKeyId
 * @param accessKeySecret 临时 AccessKeySecret
 * @param securityToken   安全令牌（STS 必备）
 * @param expiration      过期时间（ISO-8601，如 {@code 2026-10-12T09:30:00Z}）
 */
public record StsCredential(
        String accessKeyId,
        String accessKeySecret,
        String securityToken,
        String expiration) {

    /** 覆盖默认的 record toString：**绝不回显密钥明文**（防止被误 log）。 */
    @Override
    public String toString() {
        return "StsCredential[accessKeyId=***, accessKeySecret=***, securityToken=***, expiration=" + expiration + "]";
    }
}
