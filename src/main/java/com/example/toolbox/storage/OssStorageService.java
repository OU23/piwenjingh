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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * 阿里云 OSS 存储实现（M-22 / E-01）。
 *
 * <p><b>隔离约束（E-02，硬要求）</b>：OSS 的初始化与全部调用都包在**独立错误边界**内，
 * 异常**一律转 {@link Code#STORAGE_OSS_UNAVAILABLE}（2006）**，不得抛到全局、不得 500；
 * OSS 故障时登录 / 检索 / 本地文件读写**不受影响**。
 *
 * <p><b>客户端生命周期</b>：{@link OSS} **单例 + 懒加载**（线程安全），进程退出时关闭。
 * 未配置时**不建立连接**（避免"配错就把服务拖挂"）。
 *
 * <p><b>原子性</b>：对象存储天然满足"内容完整后对象才可见"，无需临时文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OssStorageService implements StorageService {

    private final OssProperties ossProperties;
    private final StorageProperties storageProperties;

    private volatile OSS client;

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

    @PreDestroy
    void shutdown() {
        OSS c = client;
        if (c != null) {
            try {
                c.shutdown();
            } catch (RuntimeException e) {
                log.warn("[OSS] 关闭客户端异常：{}", e.getMessage());
            }
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
        try {
            client().putObject(ossProperties.getBucket(), storageKey, data, metadata);
            return storageKey;
        } catch (OSSException | ClientException e) {
            throw ossUnavailable("写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public InputStream open(String storageKey) throws IOException {
        try {
            OSSObject object = client().getObject(ossProperties.getBucket(), storageKey);
            if (object == null) {
                throw new FileNotFoundException("存储键不存在：" + storageKey);
            }
            return object.getObjectContent();
        } catch (OSSException e) {
            // NoSuchKey 属于"资源已不存在"，交给上层按既有口径处理
            if (isNoSuchKey(e)) {
                throw new NoSuchFileException("存储键不存在：" + storageKey);
            }
            throw ossUnavailable("读取失败：" + e.getMessage(), e);
        } catch (ClientException e) {
            throw ossUnavailable("读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        try {
            return client().doesObjectExist(ossProperties.getBucket(), storageKey);
        } catch (OSSException | ClientException e) {
            throw ossUnavailable("探测失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String storageKey) throws IOException {
        try {
            client().deleteObject(ossProperties.getBucket(), storageKey);
        } catch (OSSException | ClientException e) {
            throw ossUnavailable("删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public long size(String storageKey) throws IOException {
        try {
            ObjectMetadata metadata = client().getObjectMetadata(ossProperties.getBucket(), storageKey);
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
        }
    }

    @Override
    public String generateKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public Optional<String> presignedUrl(String storageKey, String downloadFileName, Duration ttl) {
        try {
            GeneratePresignedUrlRequest request =
                    new GeneratePresignedUrlRequest(ossProperties.getBucket(), storageKey);
            request.setExpiration(Date.from(Instant.now().plus(ttl)));
            if (downloadFileName != null && !downloadFileName.isBlank()) {
                // 直链下载时以签名参数覆盖文件名（对象键是不可读的 UUID）。
                // ⚠️ SDK 3.17.x 无 setResponseContentDisposition，须用 addQueryParameter 透传 response-content-disposition。
                request.addQueryParameter("response-content-disposition", buildContentDisposition(downloadFileName));
            }
            URL url = client().generatePresignedUrl(request);
            return Optional.of(url.toString());
        } catch (OSSException | ClientException e) {
            throw ossUnavailable("签发下载地址失败：" + e.getMessage(), e);
        }
    }

    @Override
    public StorageBackend backend() {
        return StorageBackend.OSS;
    }

    // ---------------- 内部 ----------------

    /** 懒加载单例客户端；未配置时给出明确错误（不建立连接） */
    private OSS client() {
        OSS c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    if (!ossProperties.configured()) {
                        throw new BusinessException(Code.STORAGE_OSS_UNAVAILABLE, "对象存储未配置");
                    }
                    c = new OSSClientBuilder().build(
                            ossProperties.getEndpoint(),
                            ossProperties.getAccessKeyId(),
                            ossProperties.getAccessKeySecret());
                    client = c;
                }
            }
        }
        return c;
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
