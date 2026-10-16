package com.example.toolbox.storage;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.upload.service.OssStsCredentialIssuer;
import com.example.toolbox.upload.service.StsCredential;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * 阿里云 OSS 存储实现（M-22 / E-01）。
 *
 * <p><b>身份模型（M-23 修订 / 2026-10-16）</b>：本类**不再用长期 AK 直连 OSS**。
 * 长期 AK 只用于向 STS 换取临时凭证（见 {@link OssStsCredentialIssuer}）；
 * 每一次对象操作都**新建一个携带临时凭证（含 SecurityToken）的客户端，用完即关**，
 * 且凭证的 {@code Resource} 精确到该对象、{@code Action} 按用途最小授予
 * （读 {@code GetObject} / 写 {@code PutObject} / 删 {@code DeleteObject}）。
 *
 * <p><b>隔离约束（E-02，硬要求）</b>：OSS 的初始化与全部调用都包在**独立错误边界**内，
 * 异常**一律转 {@link Code#STORAGE_OSS_UNAVAILABLE}（2006）**，不得抛到全局、不得 500；
 * OSS 故障时登录 / 检索 / 本地文件读写**不受影响**。
 *
 * <p><b>原子性</b>：对象存储天然满足"内容完整后对象才可见"，无需临时文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OssStorageService implements StorageService {

    /**
     * 签名 URL 的过期时间最多贴近"临时凭证剩余有效期 − 本边界"。
     * 依据阿里云规则：STS 凭证与签名 URL 都设有效期时**以最小者为准**，
     * 故不能让 URL 的有效期越出凭证剩余寿命，否则会出现"链接名义上未过期、实际已失效"。
     */
    private static final Duration CREDENTIAL_SAFETY_MARGIN = Duration.ofSeconds(60);

    private final OssProperties ossProperties;
    private final StorageProperties storageProperties;
    private final OssStsCredentialIssuer credentialIssuer;

    /**
     * 配置校验（A16）：仅当写入后端 = oss 时，要求 OSS 凭据齐备，**启动即失败**（可预期的运维配置错误）。
     * 写入后端 = local 时不做检查 —— 此时 OSS 是可选项，不得影响启动（E-02）。
     */
    @PostConstruct
    void validateConfiguration() {
        if (storageProperties.writeBackend() == StorageBackend.OSS && !ossProperties.configured()) {
            throw new IllegalStateException(
                    "存储后端已设为 oss，但 OSS 配置不完整：请设置环境变量 "
                            + "OSS_ENDPOINT / OSS_BUCKET / OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET");
        }
    }

    @Override
    public String save(String storageKey, InputStream data, long size) throws IOException {
        if (size <= 0) {
            // 对象存储需要已知长度才能流式上传（本地实现无此约束）
            throw new BusinessException(Code.SYS_ERROR, "对象存储写入需要已知文件长度（size=" + size + "）");
        }
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(size);
        OSS client = null;
        try {
            client = clientFor(credentialIssuer.issueForWrite(storageKey));
            client.putObject(ossProperties.getBucket(), storageKey, data, metadata);
            return storageKey;
        } catch (OSSException | ClientException e) {
            throw ossUnavailable("写入失败：" + e.getMessage(), e);
        } finally {
            shutdownQuietly(client);
        }
    }

    @Override
    public InputStream open(String storageKey) throws IOException {
        OSS client = null;
        boolean handedOff = false;
        try {
            client = clientFor(credentialIssuer.issueForRead(storageKey));
            OSSObject object = client.getObject(ossProperties.getBucket(), storageKey);
            if (object == null) {
                throw new FileNotFoundException("存储键不存在：" + storageKey);
            }
            OSS owned = client;
            handedOff = true;
            // 返回的是**网络流**：客户端必须活到流被关闭为止，故此处不 finally 关闭，
            // 而是把关闭动作挂到流上（接口约定：调用方负责关闭流 → 关闭时释放连接）。
            return new FilterInputStream(object.getObjectContent()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        shutdownQuietly(owned);
                    }
                }
            };
        } catch (OSSException e) {
            // NoSuchKey 属于"资源已不存在"，交给上层按既有口径处理
            if (isNoSuchKey(e)) {
                throw new NoSuchFileException("存储键不存在：" + storageKey);
            }
            throw ossUnavailable("读取失败：" + e.getMessage(), e);
        } catch (ClientException e) {
            throw ossUnavailable("读取失败：" + e.getMessage(), e);
        } finally {
            if (!handedOff) {
                shutdownQuietly(client);
            }
        }
    }

    @Override
    public boolean exists(String storageKey) {
        OSS client = null;
        try {
            client = clientFor(credentialIssuer.issueForRead(storageKey));
            return client.doesObjectExist(ossProperties.getBucket(), storageKey);
        } catch (OSSException | ClientException e) {
            throw ossUnavailable("探测失败：" + e.getMessage(), e);
        } finally {
            shutdownQuietly(client);
        }
    }

    @Override
    public void delete(String storageKey) throws IOException {
        OSS client = null;
        try {
            client = clientFor(credentialIssuer.issueForDelete(storageKey));
            client.deleteObject(ossProperties.getBucket(), storageKey);
        } catch (OSSException | ClientException e) {
            throw ossUnavailable("删除失败：" + e.getMessage(), e);
        } finally {
            shutdownQuietly(client);
        }
    }

    @Override
    public long size(String storageKey) throws IOException {
        OSS client = null;
        try {
            client = clientFor(credentialIssuer.issueForRead(storageKey));
            ObjectMetadata metadata = client.getObjectMetadata(ossProperties.getBucket(), storageKey);
            if (metadata == null) {
                throw new NoSuchFileException("存储键不存在：" + storageKey);
            }
            return metadata.getContentLength();
        } catch (OSSException e) {
            if (isNoSuchKey(e)) {
                throw new NoSuchFileException("存储键不存在：" + storageKey);
            }
            throw ossUnavailable("读取元数据失败：" + e.getMessage(), e);
        } catch (ClientException e) {
            throw ossUnavailable("读取元数据失败：" + e.getMessage(), e);
        } finally {
            shutdownQuietly(client);
        }
    }

    @Override
    public String generateKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public Optional<String> presignedUrl(String storageKey, String downloadFileName, Duration ttl) {
        OSS signer = null;
        try {
            // 签名者必须自身具备 oss:GetObject，第三方才能凭该 URL 下载（阿里云明文规则）。
            StsCredential credential = credentialIssuer.issueForRead(storageKey);
            GeneratePresignedUrlRequest request =
                    new GeneratePresignedUrlRequest(ossProperties.getBucket(), storageKey);
            request.setExpiration(Date.from(signableExpiry(credential, ttl)));
            if (downloadFileName != null && !downloadFileName.isBlank()) {
                // 直链下载时以签名参数覆盖文件名（对象键是不可读的 UUID）。
                // ⚠️ SDK 3.17.x 无 setResponseContentDisposition，须用 addQueryParameter 透传 response-content-disposition。
                request.addQueryParameter("response-content-disposition", buildContentDisposition(downloadFileName));
            }
            signer = clientFor(credential);
            URL url = signer.generatePresignedUrl(request);
            return Optional.of(url.toString());
        } catch (OSSException | ClientException e) {
            throw ossUnavailable("签发下载地址失败：" + e.getMessage(), e);
        } finally {
            shutdownQuietly(signer);
        }
    }

    @Override
    public StorageBackend backend() {
        return StorageBackend.OSS;
    }

    // ---------------- 内部 ----------------

    /** 用临时凭证（含 SecurityToken）构造一次性客户端 —— **不携带任何长期密钥**。 */
    private OSS clientFor(StsCredential credential) {
        return new OSSClientBuilder().build(ossProperties.getEndpoint(),
                credential.accessKeyId(), credential.accessKeySecret(), credential.securityToken());
    }

    private void shutdownQuietly(OSS client) {
        if (client != null) {
            try {
                client.shutdown();
            } catch (RuntimeException e) {
                // 不让资源释放异常覆盖业务结果，也不记录可能含凭据的异常报文。
                log.warn("[OSS] 关闭临时凭据客户端异常");
            }
        }
    }

    /**
     * 签名 URL 的过期时间 = min(请求 ttl, 临时凭证剩余有效期 − 安全边界)。
     * 阿里云规则：STS 凭证与签名 URL 同时设置有效期时，以**最小者**为准。
     */
    private Instant signableExpiry(StsCredential credential, Duration ttl) {
        Instant now = Instant.now();
        Instant wanted = now.plus(ttl);
        Instant cap;
        try {
            cap = Instant.parse(credential.expiration()).minus(CREDENTIAL_SAFETY_MARGIN);
        } catch (DateTimeParseException | NullPointerException e) {
            cap = now; // 无法判定凭证有效期 → 视为不足，走下方报错
        }
        Instant effective = wanted.isBefore(cap) ? wanted : cap;
        if (!effective.isAfter(now)) {
            throw ossUnavailable("临时凭证剩余有效期不足，无法签发下载地址", null);
        }
        return effective;
    }

    private BusinessException ossUnavailable(String detail, Throwable cause) {
        log.warn("[OSS] {}（已按 E-02 隔离为 2006）", detail);
        return new BusinessException(Code.STORAGE_OSS_UNAVAILABLE, "对象存储暂不可用");
    }

    private boolean isNoSuchKey(OSSException e) {
        String code = e.getErrorCode();
        return "NoSuchKey".equalsIgnoreCase(code) || "NoSuchObject".equalsIgnoreCase(code);
    }

    /** 与 DownloadController 同款：ASCII 名 + UTF-8 名双写（支持中文文件名） */
    private String buildContentDisposition(String fileName) {
        String ascii = fileName.replaceAll("[^\\x20-\\x7e]", "_").replace("\"", "'");
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
