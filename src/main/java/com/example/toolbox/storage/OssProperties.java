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

    /** 配置是否完整（四项凭据齐备） */
    public boolean configured() {
        return notBlank(endpoint) && notBlank(bucket) && notBlank(accessKeyId) && notBlank(accessKeySecret);
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
