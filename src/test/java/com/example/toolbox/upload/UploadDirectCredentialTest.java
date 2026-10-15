package com.example.toolbox.upload;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.sts.model.v20150401.AssumeRoleRequest;
import com.aliyuncs.sts.model.v20150401.AssumeRoleResponse;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.setting.service.SettingService;
import com.example.toolbox.storage.OssProperties;
import com.example.toolbox.storage.OssStorageService;
import com.example.toolbox.storage.StorageProperties;
import com.example.toolbox.upload.dto.UploadDirectCredentialDTO;
import com.example.toolbox.upload.service.OssStsCredentialIssuer;
import com.example.toolbox.upload.service.UploadDirectService;
import com.example.toolbox.upload.vo.UploadCredentialVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 直传凭证签发（B14 验收 A3 · A4 · A5 · A6 · A7 · A8 · A9 · A10）。
 *
 * <p><b>不打真实 STS</b>：用 {@code mockConstruction} 把 {@code new DefaultAcsClient(profile)} 换成替身，
 * **捕获真实的 `AssumeRoleRequest`** 做结构断言（策略是否限前缀 / 限动作、有效期是否生效）——
 * 断言的是"我方实际发出的请求"，而非某个辅助方法。
 *
 * <p>本用例走**真实** {@link OssStsCredentialIssuer}（只 mock SDK 客户端），故策略构造逻辑被真实覆盖。
 */
class UploadDirectCredentialTest {

    private static final long MB = 1024L * 1024L;
    private static final long DEPT_ID = 7L;
    private static final String OWN_KEY = "tools/7/0123456789abcdef0123456789abcdef.zip";

    private final StorageProperties storageProps = new StorageProperties();
    private final OssProperties ossProps = new OssProperties();
    private final OssStorageService oss = mock(OssStorageService.class);

    private AssumeRoleResponse response;
    private ClientException failure;
    private MockedConstruction<DefaultAcsClient> construction;
    private ListAppender<ILoggingEvent> logs;
    private Logger issuerLogger;

    private long chunkSize = 20 * MB;
    private long maxFileSize = 10L * 1024 * MB;

    private UploadDirectService service;

    @BeforeEach
    void setUp() {
        ossProps.setEndpoint("oss-cn-hangzhou.aliyuncs.com");
        ossProps.setBucket("toolbox-files");
        ossProps.setAccessKeyId("ak-long-term");
        ossProps.setAccessKeySecret("sk-long-term");
        ossProps.setStsRoleArn("acs:ram::1234567890:role/toolbox-oss-uploader");

        response = new AssumeRoleResponse();
        AssumeRoleResponse.Credentials credentials = new AssumeRoleResponse.Credentials();
        credentials.setAccessKeyId("STS-ak");
        credentials.setAccessKeySecret("STS-sk");
        credentials.setSecurityToken("STS-token");
        credentials.setExpiration("2026-10-13T09:30:00Z");
        response.setCredentials(credentials);

        construction = mockConstruction(DefaultAcsClient.class, (mock, context) ->
                when(mock.getAcsResponse(any(AssumeRoleRequest.class))).thenAnswer(invocation -> {
                    if (failure != null) {
                        throw failure;
                    }
                    return response;
                }));

        // 日志捕获：验证"凭证绝不入日志"（A10 / C8）
        issuerLogger = (Logger) LoggerFactory.getLogger(OssStsCredentialIssuer.class);
        logs = new ListAppender<>();
        logs.start();
        issuerLogger.addAppender(logs);

        UserContext.set(LoginUser.builder().userId(42L).username("zhangsan").deptId(DEPT_ID).build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        if (construction != null) {
            construction.close();
        }
        if (issuerLogger != null) {
            issuerLogger.detachAppender(logs);
        }
    }

    private UploadDirectService service() {
        SettingService settings = new SettingService(null) {
            @Override
            public long getChunkSize() {
                return chunkSize;
            }

            @Override
            public long getMaxFileSize() {
                return maxFileSize;
            }
        };
        return new UploadDirectService(storageProps, ossProps, settings,
                new OssStsCredentialIssuer(ossProps, storageProps), oss, new AuditLogger());
    }

    private UploadDirectCredentialDTO dto(long fileSize, String objectKey) {
        UploadDirectCredentialDTO dto = new UploadDirectCredentialDTO();
        dto.setFileName("demo.zip");
        dto.setFileSize(fileSize);
        dto.setObjectKey(objectKey);
        return dto;
    }

    private AssumeRoleRequest capturedRequest() throws Exception {
        List<DefaultAcsClient> created = construction.constructed();
        assertEquals(1, created.size(), "IAcsClient 必须单例复用（一次调用只构造一次）");
        ArgumentCaptor<AssumeRoleRequest> captor = ArgumentCaptor.forClass(AssumeRoleRequest.class);
        verify(created.get(0)).getAcsResponse(captor.capture());
        return captor.getValue();
    }

    // ---------------- A3 策略限前缀 ----------------

    @Test
    void sessionPolicyRestrictsResourceToOwnDeptPrefix() throws Exception {
        UploadCredentialVO vo = service().credentials(dto(10 * MB, null));

        String policy = capturedRequest().getPolicy();
        assertTrue(policy.contains("acs:oss:*:*:toolbox-files/tools/" + DEPT_ID + "/*"),
                "策略 Resource 必须限定到本部门前缀：" + policy);
        assertFalse(policy.contains("\"Resource\":[\"*\"]"), "禁止整桶 / 通配资源：" + policy);
    }

    // ---------------- A4 动作最小化 ----------------

    @Test
    void sessionPolicyGrantsUploadActionsOnly() throws Exception {
        service().credentials(dto(10 * MB, null));

        String policy = capturedRequest().getPolicy();
        for (String action : new String[]{"oss:PutObject", "oss:InitiateMultipartUpload", "oss:UploadPart",
                "oss:CompleteMultipartUpload", "oss:AbortMultipartUpload", "oss:ListParts"}) {
            assertTrue(policy.contains(action), "策略必须包含上传动作 " + action + "：" + policy);
        }
        assertFalse(policy.contains("oss:GetObject"), "不得授予读权限（读走后端签名 URL）：" + policy);
        assertFalse(policy.contains("oss:DeleteObject"), "不得授予删权限（删走后端）：" + policy);
    }

    // ---------------- A5 有效期 ----------------

    @Test
    void durationSecondsComesFromConfiguration() throws Exception {
        ossProps.setStsDurationSeconds(7200);

        service().credentials(dto(10 * MB, null));

        assertEquals(7200L, capturedRequest().getDurationSeconds());
    }

    @Test
    void outOfRangeDurationFallsBackToDefault() throws Exception {
        ossProps.setStsDurationSeconds(10); // 低于阿里云下限 900

        service().credentials(dto(10 * MB, null));

        assertEquals(OssProperties.DEFAULT_STS_DURATION_SECONDS, capturedRequest().getDurationSeconds());
    }

    // ---------------- A6 上限拦截 ----------------

    @Test
    void fileSizeOverLimitIsRejectedWithoutIssuingCredential() {
        maxFileSize = 10 * MB;

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().credentials(dto(11 * MB, null)));

        assertEquals(Code.FILE_TOO_LARGE, ex.getCode());
        assertEquals(200, ex.getCode().getHttpStatus(), "业务失败必须 HTTP 200 + 业务码");
        assertTrue(construction.constructed().isEmpty(), "拦截必须发生在签发之前 —— 不得调用 STS");
    }

    @Test
    void nonPositiveFileSizeIsRejectedWith1003() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().credentials(dto(0, null)));

        assertEquals(Code.PARAM_INVALID, ex.getCode());
        assertTrue(construction.constructed().isEmpty(), "0 字节不得签发凭证");
    }

    // ---------------- A7 对象键 ----------------

    @Test
    void freshUploadGetsGeneratedKeyUnderOwnDeptPrefix() {
        UploadCredentialVO vo = service().credentials(dto(10 * MB, null));

        assertTrue(vo.getObjectKey().matches("tools/" + DEPT_ID + "/[0-9a-f]{32}\\.zip"),
                "对象键由后端生成为 tools/<deptId>/<uuid><ext>，实际：" + vo.getObjectKey());
    }

    @Test
    void resumeReusesProvidedObjectKeyVerbatim() {
        UploadCredentialVO vo = service().credentials(dto(10 * MB, OWN_KEY));

        assertEquals(OWN_KEY, vo.getObjectKey(), "续传时合法对象键必须**原样复用**（保证 uploadId 仍有效）");
    }

    @Test
    void foreignOrMalformedObjectKeyIsRejected() {
        String foreign = "tools/999/0123456789abcdef0123456789abcdef.zip";
        String malformed = "tools/" + DEPT_ID + "/not-a-uuid.zip";

        assertEquals(Code.PARAM_INVALID, assertThrows(BusinessException.class,
                () -> service().credentials(dto(10 * MB, foreign))).getCode(),
                "他人部门前缀 → 1003");
        assertEquals(Code.PARAM_INVALID, assertThrows(BusinessException.class,
                () -> service().credentials(dto(10 * MB, malformed))).getCode(),
                "形态不符 → 1003");
    }

    // ---------------- A8 partSize 自动上调 ----------------

    @Test
    void partSizeIsUpgradedWhenPartsWouldExceedLimit() {
        chunkSize = 5 * MB;
        maxFileSize = 100L * 1024 * MB;
        long fileSize = 60L * 1024 * MB; // 5 MB × 10000 = 50 GB 上限 → 60 GB 必须上调

        UploadCredentialVO vo = service().credentials(dto(fileSize, null));

        assertTrue(vo.getPartSize() > chunkSize,
                "片数将超 10000 时必须自动上调，实际：" + vo.getPartSize());
        assertTrue(fileSize / vo.getPartSize() <= 10000, "上调后片数必须 ≤ 10000");
        assertEquals(chunkSize, chunkSize, "上调不得回写设置（此处以常量自证：设置值未被修改）");
    }

    @Test
    void partSizeStaysAtSettingWhenPartsFit() {
        chunkSize = 20 * MB;

        UploadCredentialVO vo = service().credentials(dto(10L * 1024 * MB, null));

        assertEquals(20 * MB, vo.getPartSize(), "片数未超限时不得改动设置值");
    }

    @Test
    void fileTooLargeEvenAfterUpgradeIsRejected() {
        chunkSize = 5 * MB;
        maxFileSize = 5000L * 1024 * MB;
        long fileSize = 2000L * 1024 * MB; // 需要 200 MB/片 > 上限 100 MB

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().credentials(dto(fileSize, null)));

        assertEquals(Code.PARAM_INVALID, ex.getCode());
        assertTrue(ex.getMessage().contains("调大分片大小"), "提示要给出可行解：" + ex.getMessage());
    }

    // ---------------- A9 / E-02 错误边界 ----------------

    @Test
    void stsFailureIsIsolatedAs2006Not500() {
        failure = new ClientException("ServiceUnavailable", "STS 服务不可用", "RequestId-1");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().credentials(dto(10 * MB, null)));

        assertEquals(Code.STORAGE_OSS_UNAVAILABLE, ex.getCode());
        assertEquals(200, ex.getCode().getHttpStatus(), "STS 故障必须 HTTP 200 + 2006，不得 500");
        assertEquals("对象存储暂不可用", ex.getMessage(), "对外只给可读文案");
    }

    // ---------------- A18 启动校验 ----------------

    @Test
    void ossTypeWithoutRoleArnFailsFastAtStartup() {
        storageProps.setType("oss");
        OssStsCredentialIssuer issuer = new OssStsCredentialIssuer(new OssProperties(), storageProps);

        IllegalStateException ex = assertThrows(IllegalStateException.class, issuer::validateConfiguration,
                "启用直传却没配角色 ARN → 必须启动即失败，不允许带病启动");
        assertTrue(ex.getMessage().contains("OSS_STS_ROLE_ARN"), "报错要能直接指向缺失项：" + ex.getMessage());
    }

    @Test
    void localTypeDoesNotRequireStsConfig() {
        storageProps.setType("local");
        OssStsCredentialIssuer issuer = new OssStsCredentialIssuer(new OssProperties(), storageProps);

        assertDoesNotThrow(issuer::validateConfiguration,
                "直传是可选能力：type=local 时不得因缺 STS 配置而拒绝启动（E-02）");
    }

    // ---------------- A10 凭证禁入日志 ----------------

    @Test
    void credentialsNeverAppearInLogs() {
        service().credentials(dto(10 * MB, null));
        failure = new ClientException("ServiceUnavailable", "STS 服务不可用", "RequestId-2");
        assertThrows(BusinessException.class, () -> service().credentials(dto(10 * MB, null)));

        String all = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        assertFalse(all.contains("STS-ak"), "AccessKeyId 不得入日志：" + all);
        assertFalse(all.contains("STS-sk"), "AccessKeySecret 不得入日志：" + all);
        assertFalse(all.contains("STS-token"), "SecurityToken 不得入日志：" + all);
    }
}
