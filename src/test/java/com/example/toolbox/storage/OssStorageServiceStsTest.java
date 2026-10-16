package com.example.toolbox.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.upload.service.OssStsCredentialIssuer;
import com.example.toolbox.upload.service.StsCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归测试（2026-10-16）：**OSS 的所有链路都必须走 STS 临时凭证**，绝不复用长期 AK 直连。
 *
 * <p>★ 贴合线上症状：上传（浏览器直传）已通，但<b>下载 403</b> ——
 * 报错 {@code You have no right to access this object because of bucket acl.}，
 * 而 URL 里 {@code OSSAccessKeyId=LTAI...}（长期 AK）、{@code AuthPrincipalType=SubUser}。
 * 根因：{@code presignedUrl} 仍用长期 AK 签名，而该 RAM 用户对桶没有 {@code oss:GetObject}。
 * 依据：阿里云明文 ——「生成预签名 URL 时需拥有 {@code oss:GetObject} 权限，第三方才能下载成功」。
 *
 * <p><b>熔断（P0）</b>：把任一操作退回 {@code build(endpoint, ak, sk)}（长期 AK）→
 * {@link #everyOperationUsesStsCredentialNeverLongTermKey} 必红。
 */
class OssStorageServiceStsTest {

    private static final String BUCKET = "toolbox-files";
    private static final String ENDPOINT = "oss-cn-beijing.aliyuncs.com";
    private static final String KEY = "tools/1/f0dc70e1.zip";

    private OssProperties props;
    private StorageProperties storageProps;
    private OssStsCredentialIssuer issuer;
    private OSS oss;
    private OssStorageService service;

    @BeforeEach
    void setUp() {
        props = new OssProperties();
        props.setEndpoint(ENDPOINT);
        props.setBucket(BUCKET);
        props.setAccessKeyId("LTAI-long-term-ak");
        props.setAccessKeySecret("long-term-sk");

        storageProps = new StorageProperties();
        oss = mock(OSS.class);
        issuer = mock(OssStsCredentialIssuer.class);
        service = new OssStorageService(props, storageProps, issuer);
    }

    /** 桩住客户端构造：3 参（长期 AK）与 4 参（STS）都返回同一 mock —— 便于用 verify 判定"用了哪一条"。 */
    private org.mockito.MockedConstruction<OSSClientBuilder> stubClient() {
        return mockConstruction(OSSClientBuilder.class, (builder, context) -> {
            when(builder.build(anyString(), anyString(), anyString(), anyString())).thenReturn(oss);
            when(builder.build(anyString(), anyString(), anyString())).thenReturn(oss);
        });
    }

    private StsCredential credential(Duration remaining) {
        return new StsCredential("STS.ak", "STS-sk", "STS-token",
                Instant.now().plus(remaining).toString());
    }

    private void stubIssue(Duration remaining) {
        StsCredential c = credential(remaining);
        when(issuer.issueForRead(KEY)).thenReturn(c);
        when(issuer.issueForVerification(KEY)).thenReturn(c);
        when(issuer.issueForWrite(KEY)).thenReturn(c);
        when(issuer.issueForDelete(KEY)).thenReturn(c);
    }

    /**
     * ★ 核心用例：六个入口**全部**用 STS 客户端（4 参 build），**从不**调用 3 参 build（长期 AK）。
     * 熔断：任一操作退回长期 AK → 本用例必红。
     */
    @Test
    void everyOperationUsesStsCredentialNeverLongTermKey() throws Exception {
        stubIssue(Duration.ofHours(2));
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(8);
        OSSObject object = new OSSObject();
        object.setObjectContent(new ByteArrayInputStream(new byte[8]));
        when(oss.doesObjectExist(BUCKET, KEY)).thenReturn(true);
        when(oss.getObjectMetadata(BUCKET, KEY)).thenReturn(metadata);
        when(oss.getObject(BUCKET, KEY)).thenReturn(object);
        when(oss.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
                .thenReturn(new URL("https://" + BUCKET + "." + ENDPOINT + "/" + KEY + "?Expires=1&Signature=x"));

        try (var builders = stubClient()) {
            service.exists(KEY);
            service.size(KEY);
            service.save(KEY, new ByteArrayInputStream(new byte[8]), 8);
            try (InputStream in = service.open(KEY)) {
                in.readAllBytes();
            }
            service.delete(KEY);
            service.presignedUrl(KEY, "a.zip", Duration.ofHours(1));

            assertEquals(6, builders.constructed().size(), "六个入口应各构造一次 STS 客户端");

            for (OSSClientBuilder builder : builders.constructed()) {
                verify(builder).build(ENDPOINT, "STS.ak", "STS-sk", "STS-token");
                verify(builder, never()).build(anyString(), anyString(), anyString());
            }

            verify(oss, times(6)).shutdown();
        }
    }

    /** 签名 URL 的过期必须被**临时凭证剩余有效期**压住（阿里云：STS 与签名 URL 取最小者为准）。 */
    @Test
    void presignedUrlExpiryIsCappedByCredentialRemainingLife() throws Exception {
        stubIssue(Duration.ofMinutes(10));
        when(oss.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
                .thenReturn(new URL("https://" + BUCKET + "." + ENDPOINT + "/" + KEY + "?Signature=z"));

        try (var ignored = stubClient()) {
            // 请求 1 小时，但凭证只剩 10 分钟 → 实际有效期必须 ≤ 10 分钟
            service.presignedUrl(KEY, "a.zip", Duration.ofHours(1));

            var captor = org.mockito.ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
            verify(oss).generatePresignedUrl(captor.capture());
            Date expiry = captor.getValue().getExpiration();
            long seconds = (expiry.getTime() - System.currentTimeMillis()) / 1000;
            assertTrue(seconds > 0 && seconds <= 10 * 60,
                    "URL 过期必须被临时凭证剩余有效期压住（实测剩余 " + seconds + "s）");
        }
    }

    /** STS 签发失败 → 直接 2006，**绝不回退长期 AK**（不构造任何客户端）。 */
    @Test
    void stsFailureNeverFallsBackToLongTermKey() {
        BusinessException down = new BusinessException(Code.STORAGE_OSS_UNAVAILABLE, "对象存储暂不可用");
        when(issuer.issueForRead(KEY)).thenThrow(down);
        when(issuer.issueForWrite(KEY)).thenThrow(down);
        when(issuer.issueForDelete(KEY)).thenThrow(down);

        try (var builders = stubClient()) {
            assertOssUnavailable(() -> service.exists(KEY));
            assertOssUnavailable(() -> service.delete(KEY));
            assertOssUnavailable(() -> service.save(KEY, new ByteArrayInputStream(new byte[8]), 8));
            assertOssUnavailable(() -> service.presignedUrl(KEY, "a.zip", Duration.ofHours(1)));

            assertTrue(builders.constructed().isEmpty(),
                    "STS 不可用时不得构造 OSS 客户端（更不允许用长期 AK 兜底）");
        }
    }

    /** open 返回的是网络流：客户端必须活到流关闭为止，否则流会不可读。 */
    @Test
    void openKeepsClientAliveUntilStreamClosed() throws Exception {
        stubIssue(Duration.ofHours(2));
        OSSObject object = new OSSObject();
        object.setObjectContent(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(oss.getObject(BUCKET, KEY)).thenReturn(object);

        try (var ignored = stubClient()) {
            InputStream in = service.open(KEY);
            verify(oss, never()).shutdown();

            assertArrayEquals(new byte[]{1, 2, 3}, in.readAllBytes());
            in.close();
            verify(oss).shutdown();
        }
    }

    private void assertOssUnavailable(org.junit.jupiter.api.function.Executable action) {
        BusinessException ex = assertThrows(BusinessException.class, action);
        assertEquals(Code.STORAGE_OSS_UNAVAILABLE, ex.getCode());
        assertEquals(200, ex.getCode().getHttpStatus(), "OSS 故障必须 HTTP 200 + 2006，不得 500");
    }
}
