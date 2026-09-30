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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OSS 存储实现（M-22 / B13 验收 A2 · A3 · A14 · A16 · C8）。
 *
 * <p>**不打真实 OSS**：用 {@code mockConstruction} 把 {@code new OSSClientBuilder().build(...)}
 * 替换为 mock 客户端（Mockito 5 内联 mock maker 默认启用），只验证"我方发出的调用/元数据/错误映射"。
 *
 * <p>重点：
 * <ul>
 *   <li><b>A3</b>：{@code save} 必须带<b>已知长度</b>与桶名（对象存储不接受未知长度流）；</li>
 *   <li><b>A14 / E-02</b>：任何 OSS 调用异常一律映射为 {@code 2006} 且 **HTTP 200**（不得 500 外泄）；</li>
 *   <li>「资源已不存在」（NoSuchKey）**不算故障**，按既有口径抛 {@link NoSuchFileException}；</li>
 *   <li><b>A16</b>：{@code type=oss} 但凭据不全 → **启动即失败**；{@code type=local} 时不检查（OSS 是可选项）。</li>
 * </ul>
 */
class OssStorageServiceTest {

    private static final String BUCKET = "toolbox-files";
    private static final String SIGNED =
            "https://toolbox-files.oss-cn-hangzhou.aliyuncs.com/tools/k.zip?Expires=1759212600&Signature=x";

    private OssProperties props;
    private StorageProperties storageProps;
    private OSS oss;
    private OssStorageService service;

    @BeforeEach
    void setUp() {
        props = new OssProperties();
        props.setEndpoint("oss-cn-hangzhou.aliyuncs.com");
        props.setBucket(BUCKET);
        props.setAccessKeyId("ak");
        props.setAccessKeySecret("sk");

        storageProps = new StorageProperties();
        oss = mock(OSS.class);
        service = new OssStorageService(props, storageProps);
    }

    /** 桩住客户端构造：{@code new OSSClientBuilder().build(endpoint, ak, sk)} → 返回 mock 客户端 */
    private org.mockito.MockedConstruction<OSSClientBuilder> stubClient() {
        return mockConstruction(OSSClientBuilder.class,
                (builder, context) -> when(builder.build(anyString(), anyString(), anyString())).thenReturn(oss));
    }

    // ---------------- A3 写入元数据 ----------------

    @Test
    void saveSendsKnownContentLengthAndBucket() throws Exception {
        try (var ignored = stubClient()) {
            byte[] data = new byte[128];
            String key = service.save("tools/k.zip", new ByteArrayInputStream(data), data.length);

            assertEquals("tools/k.zip", key, "存储键原样返回");

            var metadata = org.mockito.ArgumentCaptor.forClass(ObjectMetadata.class);
            verify(oss).putObject(org.mockito.ArgumentMatchers.eq(BUCKET),
                    org.mockito.ArgumentMatchers.eq("tools/k.zip"),
                    any(InputStream.class), metadata.capture());
            assertEquals(128L, metadata.getValue().getContentLength(),
                    "对象存储写入必须带已知长度（未知长度流 SDK 不接受）");
        }
    }

    /** 长度为未知（-1 / 0）→ 明确报错，**不发出半截对象**（本实现要求已知长度） */
    @Test
    void saveWithoutKnownLengthIsRejected() {
        try (var ignored = stubClient()) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.save("k", new ByteArrayInputStream(new byte[4]), -1));
            assertEquals(Code.SYS_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("长度"),
                    "应指明是「长度未知」这类可诊断原因：" + ex.getMessage());
        }
    }

    // ---------------- A2 签名 URL 与身份 ----------------

    @Test
    void presignedUrlCarriesExpiryAndDownloadFileName() throws Exception {
        try (var ignored = stubClient()) {
            when(oss.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(new URL(SIGNED));

            Optional<String> url = service.presignedUrl("tools/k.zip", "工具包 2026.zip", Duration.ofHours(1));

            assertEquals(Optional.of(SIGNED), url);
            assertEquals(StorageBackend.OSS, service.backend());

            var request = org.mockito.ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
            verify(oss).generatePresignedUrl(request.capture());
            GeneratePresignedUrlRequest captured = request.getValue();
            assertEquals(BUCKET, captured.getBucketName());
            assertEquals("tools/k.zip", captured.getKey());
            assertNotNull(captured.getExpiration(), "签名必须有过期时间（禁永久直链）");
            assertTrue(captured.getExpiration().after(new Date()), "过期时间必须在将来");

            String disposition = captured.getQueryParameter().get("response-content-disposition");
            assertNotNull(disposition,
                    "必须带 response-content-disposition：对象键是不可读 UUID，否则用户下载到乱码文件名");
            assertTrue(disposition.startsWith("attachment"), "必须按附件下载：" + disposition);
            assertTrue(disposition.contains("UTF-8''"), "中文名需 RFC 5987 编码：" + disposition);
        }
    }

    /** 文件名为空时不得带上空的 content-disposition（避免污染签名） */
    @Test
    void presignedUrlWithoutFileNameSkipsDisposition() throws Exception {
        try (var ignored = stubClient()) {
            when(oss.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(new URL(SIGNED));

            service.presignedUrl("tools/k.zip", "  ", Duration.ofHours(1));

            var request = org.mockito.ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
            verify(oss).generatePresignedUrl(request.capture());
            assertTrue(request.getValue().getQueryParameter().isEmpty(), "空文件名不应写入签名参数");
        }
    }

    // ---------------- A14 / E-02 错误边界 ----------------

    /** 任何 OSS 调用异常 → 2006（HTTP 200），不得抛到全局、不得 500 */
    @Test
    void allOperationsMapOssFailuresTo2006() throws Exception {
        try (var ignored = stubClient()) {
            ClientException network = new ClientException("网络不可达");
            OSSException serviceDown = new OSSException("ServiceUnavailable, please try again.");

            when(oss.doesObjectExist(anyString(), anyString())).thenThrow(network);
            when(oss.getObject(anyString(), anyString())).thenThrow(network);
            when(oss.getObjectMetadata(anyString(), anyString())).thenThrow(network);
            when(oss.deleteObject(anyString(), anyString())).thenThrow(serviceDown);
            when(oss.putObject(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                    .thenThrow(serviceDown);
            when(oss.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenThrow(network);

            assertOssUnavailable(() -> service.exists("k"));
            assertOssUnavailable(() -> service.open("k"));
            assertOssUnavailable(() -> service.size("k"));
            assertOssUnavailable(() -> service.delete("k"));
            assertOssUnavailable(() -> service.save("k", new ByteArrayInputStream(new byte[4]), 4));
            assertOssUnavailable(() -> service.presignedUrl("k", "a.zip", Duration.ofHours(1)));
        }
    }

    /** 未配置凭据时调用 → 2006（**不建立连接**、不抛 NPE），保证"没配 OSS 也能跑" */
    @Test
    void unconfiguredOssReportsUnavailableWithoutConnecting() {
        OssStorageService unconfigured = new OssStorageService(new OssProperties(), new StorageProperties());

        BusinessException ex = assertThrows(BusinessException.class, () -> unconfigured.exists("k"));
        assertEquals(Code.STORAGE_OSS_UNAVAILABLE, ex.getCode());
        assertEquals(200, ex.getCode().getHttpStatus(), "必须是 HTTP 200 + 业务码，不是 500");
    }

    // ---------------- "资源已不存在" 不按故障处理 ----------------

    @Test
    void noSuchKeyIsTreatedAsMissingNotAsFailure() throws Exception {
        try (var ignored = stubClient()) {
            // ⚠️ 先造好异常：Mockito 不允许在 when(...) 的参数里嵌套另一段 stubbing
            OSSException fromObject = noSuchKey();
            OSSException fromMetadata = noSuchKey();
            when(oss.getObject(anyString(), anyString())).thenThrow(fromObject);
            when(oss.getObjectMetadata(anyString(), anyString())).thenThrow(fromMetadata);

            assertThrows(NoSuchFileException.class, () -> service.open("k"),
                    "对象不存在 → 按「资源已不存在」处理（上层转 2005 / 404），不是 2006");
            assertThrows(NoSuchFileException.class, () -> service.size("k"));
        }
    }

    /** 客户端返回 null / 元数据为空时的兜底 */
    @Test
    void nullObjectOrMetadataIsTreatedAsMissing() throws Exception {
        try (var ignored = stubClient()) {
            when(oss.getObject(anyString(), anyString())).thenReturn(null);
            when(oss.getObjectMetadata(anyString(), anyString())).thenReturn(null);

            assertThrows(java.io.FileNotFoundException.class, () -> service.open("k"));
            assertThrows(NoSuchFileException.class, () -> service.size("k"));
        }
    }

    // ---------------- 正常读取路径 ----------------

    @Test
    void openAndSizeReturnRealContentAndLength() throws Exception {
        try (var ignored = stubClient()) {
            byte[] payload = {1, 2, 3};
            OSSObject object = new OSSObject();
            object.setObjectContent(new ByteArrayInputStream(payload));
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(payload.length);

            when(oss.getObject(BUCKET, "k")).thenReturn(object);
            when(oss.getObjectMetadata(BUCKET, "k")).thenReturn(metadata);
            when(oss.doesObjectExist(BUCKET, "k")).thenReturn(true);

            try (InputStream in = service.open("k")) {
                assertArrayEquals(payload, in.readAllBytes(), "读到的必须是对象内容");
            }
            assertEquals(3L, service.size("k"));
            assertTrue(service.exists("k"));
        }
    }

    // ---------------- A16 配置校验 ----------------

    @Test
    void ossTypeWithIncompleteConfigFailsFastAtStartup() {
        storageProps.setType("oss");
        OssStorageService broken = new OssStorageService(new OssProperties(), storageProps);

        IllegalStateException ex = assertThrows(IllegalStateException.class, broken::validateConfiguration,
                "写入后端已设为 oss 却没配凭据 → 必须启动即失败，不允许带病启动");
        assertTrue(ex.getMessage().contains("OSS_ENDPOINT"), "报错要能直接指向缺失项：" + ex.getMessage());
    }

    @Test
    void localTypeDoesNotRequireOssConfig() {
        storageProps.setType("local");
        OssStorageService ok = new OssStorageService(new OssProperties(), storageProps);

        assertDoesNotThrow(ok::validateConfiguration,
                "OSS 是可选能力：未配置时不得影响启动（E-02）");
    }

    /** 存储键与本地实现同形（跨后端切换不产生键冲突） */
    @Test
    void generatedKeyHasSameShapeAsLocal() {
        assertTrue(service.generateKey().matches("[A-Za-z0-9._-]{1,64}"));
    }

    // ---------------- 内部 ----------------

    private void assertOssUnavailable(org.junit.jupiter.api.function.Executable action) {
        BusinessException ex = assertThrows(BusinessException.class, action);
        assertEquals(Code.STORAGE_OSS_UNAVAILABLE, ex.getCode());
        assertEquals(200, ex.getCode().getHttpStatus(), "OSS 故障必须 HTTP 200 + 2006，不得 500");
        assertEquals("对象存储暂不可用", ex.getMessage(), "对外只给可读文案，不透出底层细节");
    }

    /** 造一个「errorCode = NoSuchKey」的 OSS 异常（SDK 构造器参数顺序易错，用 spy 更稳） */
    private OSSException noSuchKey() {
        OSSException e = spy(new OSSException("The specified key does not exist."));
        doReturn("NoSuchKey").when(e).getErrorCode();
        return e;
    }
}
