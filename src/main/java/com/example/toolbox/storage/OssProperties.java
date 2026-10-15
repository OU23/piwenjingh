package com.example.toolbox.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 阿里云 OSS 配置（{@code toolbox.oss.*}，M-22）。
 *
 * <p><b>凭据一律走环境变量</b>，配置文件里只有占位（禁止明文入库 / 入仓库 / 入日志）：
 * {@code OSS_ENDPOINT} / {@code OSS_BUCKET} / {@code OSS_ACCESS_KEY_ID} / {@code OSS_ACCESS_KEY_SECRET}。
 *
 * <p><b>endpoint 必须是公网地址</b>（如 {@code oss-cn-hangzhou.aliyuncs.com}），
 * **不得**用 {@code -internal} 内网地址 —— 直链要求"客户端浏览器可直连"。
 */
@Data
@Component
@ConfigurationProperties(prefix = "toolbox.oss")
public class OssProperties {

    private String endpoint = "";
    private String bucket = "";
    private String accessKeyId = "";
    private String accessKeySecret = "";

    /**
     * 签名 URL 有效期（秒）。默认 3600（1 小时）。
     * ⚠️ 必须足够长以覆盖"大文件下载 + 暂停后续传"——否则暂停久了 URL 过期会续不上。
     */
    private long urlTtlSeconds = 3600;

    // ---- 直传临时凭证（M-23 / B14）----

    /** 要扮演的 RAM 角色 ARN（如 {@code acs:ram::1234567890:role/toolbox-oss-uploader}）；{@code type=oss} 时必填 */
    private String stsRoleArn = "";

    /**
     * 临时凭证有效期（秒）。默认 3600；阿里云 AssumeRole 允许区间 <b>900 ~ 43200</b>。
     * 越界按默认值处理（不抛错），避免一个笔误让直传整体不可用。
     */
    private long stsDurationSeconds = DEFAULT_STS_DURATION_SECONDS;

    /** STS 服务 endpoint；一般无需改动 */
    private String stsEndpoint = "sts.aliyuncs.com";

    /** AssumeRole 默认有效期（秒） */
    public static final long DEFAULT_STS_DURATION_SECONDS = 3600L;
    /** AssumeRole 允许的最短有效期（秒），阿里云限制 */
    public static final long MIN_STS_DURATION_SECONDS = 900L;
    /** AssumeRole 允许的最长有效期（秒），阿里云限制 */
    public static final long MAX_STS_DURATION_SECONDS = 43200L;

    /** 配置是否完整（四项凭据齐备） */
    public boolean configured() {
        return notBlank(endpoint) && notBlank(bucket) && notBlank(accessKeyId) && notBlank(accessKeySecret);
    }

    /** 直传所需配置是否齐备（角色 ARN 非空） */
    public boolean stsConfigured() {
        return notBlank(stsRoleArn);
    }

    /** 生效的有效期（越界 / 非法回落默认 3600，与 urlTtl 同款容错） */
    public long stsDurationSecondsOrDefault() {
        if (stsDurationSeconds < MIN_STS_DURATION_SECONDS || stsDurationSeconds > MAX_STS_DURATION_SECONDS) {
            return DEFAULT_STS_DURATION_SECONDS;
        }
        return stsDurationSeconds;
    }

    /** 签名 URL 有效期（非法值回退 3600 秒） */
    public Duration urlTtl() {
        long s = urlTtlSeconds > 0 ? urlTtlSeconds : 3600L;
        return Duration.ofSeconds(s);
    }

    /** 供 DownloadUrlVO.expiresIn 使用 */
    public long urlTtlSecondsOrDefault() {
        return urlTtlSeconds > 0 ? urlTtlSeconds : 3600L;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
