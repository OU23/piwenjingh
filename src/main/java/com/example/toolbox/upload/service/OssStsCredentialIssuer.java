package com.example.toolbox.upload.service;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.sts.model.v20150401.AssumeRoleRequest;
import com.aliyuncs.sts.model.v20150401.AssumeRoleResponse;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.storage.OssProperties;
import com.example.toolbox.storage.StorageBackend;
import com.example.toolbox.storage.StorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.Clock;

/**
 * STS 临时凭证签发（M-23 / B14 / 方案 §5.3）。
 *
 * <p>直传的「受控例外」就落在这里（契约 §9.5 / 方案 §2.3）—— 把 <b>长期 AK/SK 下发到前端</b>是被禁止的，
 * 允许的是**后端鉴权后**签发的「短时效 + 限前缀 + 限动作」临时凭证。
 *
 * <p><b>浏览器上传凭证的三条硬约束</b>：
 * <ol>
 *   <li>内联会话策略的 {@code Resource} **必须**限定为 {@code <bucket>/tools/<deptId>/*}（不得 {@code *}）；</li>
 *   <li>{@code Action} **只给上传所需** —— 不含 {@code GetObject} / {@code DeleteObject}
 *       （读仍走后端鉴权 / 签名 URL）；</li>
 *   <li>凭证（AK/SK/Token）**禁止进入任何日志**；异常日志只留错误码与 RequestId。</li>
 * </ol>
 *
 * <p><b>错误边界（E-02）</b>：任何 STS 异常一律转 {@link Code#STORAGE_OSS_UNAVAILABLE}（2006），
 * 不得抛 500、不得影响登录 / 检索 / 本地上下传。
 *
 * <p><b>客户端生命周期</b>：{@link IAcsClient} **单例 + 懒加载**（禁止每次调用新建）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OssStsCredentialIssuer {

    /**
     * 内联会话策略授予的动作 —— **仅上传所需**。
     * ⚠️ 刻意不含 `oss:GetObject` / `oss:DeleteObject`：读与删仍走后端。
     */
    static final List<String> UPLOAD_ACTIONS = List.of(
            "oss:PutObject",
            "oss:InitiateMultipartUpload",
            "oss:UploadPart",
            "oss:CompleteMultipartUpload",
            "oss:AbortMultipartUpload",
            "oss:ListParts");

    /** 对象键的部门前缀模板：{@code <bucket>/tools/<deptId>/*} */
    static final String RESOURCE_TEMPLATE = "acs:oss:*:*:%s/tools/%d/*";

    /** 后端服务端对象操作凭证的会话名（审计标签）；沿用原取值，兼容既有缓存键与断言。 */
    static final String BACKEND_SESSION_NAME = "toolbox-verification";

    /** 后端「读」所需动作（下载签名 / 服务端读取 / 存在性与大小核实）。 */
    static final List<String> READ_ACTIONS = List.of("oss:GetObject");

    /** 后端「写」所需动作（存量迁移落盘 putObject）。 */
    static final List<String> WRITE_ACTIONS = List.of("oss:PutObject");

    /** 后端「删」所需动作（OSS 落点文件彻底删除）。 */
    static final List<String> DELETE_ACTIONS = List.of("oss:DeleteObject");

    private final OssProperties ossProperties;
    private final StorageProperties storageProperties;

    /** 单例客户端（懒加载，线程安全） */
    private volatile IAcsClient client;

    private final StsCredentialCache credentialCache = new StsCredentialCache(Clock.systemUTC(), 1024);

    /**
     * 启动校验：仅当写入后端 = oss（即启用直传）时要求 `sts-role-arn`，**启动即失败**（可预期的运维配置错误）。
     * `type=local` 时**不校验** —— 直传是可选能力，不得因缺 STS 配置而拒绝启动（E-02）。
     */
    @PostConstruct
    public void validateConfiguration() {
        if (storageProperties.writeBackend() == StorageBackend.OSS && !ossProperties.stsConfigured()) {
            throw new IllegalStateException(
                    "存储后端已设为 oss（直传），但 STS 配置缺失：请设置环境变量 OSS_STS_ROLE_ARN"
                            + "（即要扮演的 RAM 角色 ARN，见《OSS配置说明》/ 方案 §5.7）");
        }
    }

    /**
     * 签发临时凭证。
     *
     * @param userId 当前用户 ID（写入 {@code RoleSessionName}，便于云端审计追溯）
     * @param deptId 当前用户所属部门 ID（决定内联策略的资源前缀）
     * @throws BusinessException {@link Code#STORAGE_OSS_UNAVAILABLE}（2006）当 STS 不可用 / 返回不完整
     */
    public StsCredential issue(long userId, Long deptId) {
        return assumeRole("toolbox-" + userId, buildSessionPolicy(deptId));
    }

    /**
     * 后端服务端「读」凭证：复用同一角色，**Resource 精确到单个对象、仅 {@code oss:GetObject}**，绝不下发给浏览器。
     * <p>下载签名 / 服务端读取 / 存在性与大小核实**共用这一份**（同一缓存键，不重复 AssumeRole）。
     */
    public StsCredential issueForRead(String objectKey) {
        return issueForObject(objectKey, READ_ACTIONS);
    }

    /** 兼容既有调用与测试：核实（读）语义，行为与 {@link #issueForRead} 完全一致（同一缓存键）。 */
    public StsCredential issueForVerification(String objectKey) {
        return issueForRead(objectKey);
    }

    /** 后端服务端「写」凭证：精确到单个对象、仅 {@code oss:PutObject}（存量迁移落盘用）。 */
    public StsCredential issueForWrite(String objectKey) {
        return issueForObject(objectKey, WRITE_ACTIONS);
    }

    /** 后端服务端「删」凭证：精确到单个对象、仅 {@code oss:DeleteObject}（OSS 落点文件彻底删除用）。 */
    public StsCredential issueForDelete(String objectKey) {
        return issueForObject(objectKey, DELETE_ACTIONS);
    }

    /** 后端服务端对象操作凭证：Resource 精确到单对象、Action 按用途最小授予；同 (session, policy) 走缓存复用。 */
    private StsCredential issueForObject(String objectKey, List<String> actions) {
        // Resource 中的通配符会扩大授权范围，控制字符也不允许进入策略 JSON。
        if (isBlank(objectKey) || objectKey.indexOf('*') >= 0 || objectKey.indexOf('?') >= 0
                || objectKey.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(Code.PARAM_INVALID, "对象键非法");
        }
        String resource = "acs:oss:*:*:" + ossProperties.getBucket() + "/" + objectKey;
        StringBuilder actionJson = new StringBuilder();
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) {
                actionJson.append(',');
            }
            actionJson.append('"').append(actions.get(i)).append('"');
        }
        String policy = "{\"Version\":\"1\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":[" + actionJson + "],\"Resource\":[\"" + jsonEscape(resource) + "\"]}]}";
        return assumeRole(BACKEND_SESSION_NAME, policy);
    }

    private StsCredential assumeRole(String sessionName, String policy) {
        return credentialCache.get(sessionName, policy, () -> requestCredential(sessionName, policy));
    }

    private StsCredential requestCredential(String sessionName, String policy) {
        AssumeRoleRequest request = new AssumeRoleRequest();
        request.setSysEndpoint(ossProperties.getStsEndpoint());
        request.setRoleArn(ossProperties.getStsRoleArn());
        request.setRoleSessionName(sessionName);
        request.setDurationSeconds(ossProperties.stsDurationSecondsOrDefault());
        request.setPolicy(policy);

        try {
            IAcsClient sts = client();
            AssumeRoleResponse response;
            // V1 STS 客户端有共享状态，浏览器签发与后端核实共用时串行调用。
            synchronized (sts) {
                response = sts.getAcsResponse(request);
            }
            AssumeRoleResponse.Credentials c = response == null ? null : response.getCredentials();
            if (c == null || isBlank(c.getAccessKeyId()) || isBlank(c.getAccessKeySecret())
                    || isBlank(c.getSecurityToken())) {
                throw ossUnavailable("STS 返回的凭证不完整", null);
            }
            return new StsCredential(c.getAccessKeyId(), c.getAccessKeySecret(),
                    c.getSecurityToken(), c.getExpiration());
        } catch (ClientException e) {
            // ⚠️ 只记错误码 / RequestId，绝不记凭证
            log.warn("[STS] AssumeRole 失败 code={} requestId={}", e.getErrCode(), e.getRequestId());
            throw ossUnavailable("签发临时凭证失败", e);
        }
    }

    // ---------------- 内部 ----------------

    /**
     * 内联会话策略：在角色自身权限之上**进一步收窄**到「本部门前缀 + 仅上传动作」。
     * 包级可见，供结构断言（验收 A3 / A4）。
     */
    String buildSessionPolicy(Long deptId) {
        String resource = RESOURCE_TEMPLATE.formatted(ossProperties.getBucket(), deptId);
        StringBuilder actions = new StringBuilder();
        for (int i = 0; i < UPLOAD_ACTIONS.size(); i++) {
            if (i > 0) {
                actions.append(',');
            }
            actions.append('"').append(UPLOAD_ACTIONS.get(i)).append('"');
        }
        return "{\"Version\":\"1\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":["
                + actions + "],\"Resource\":[\"" + jsonEscape(resource) + "\"]}]}";
    }

    /** 懒加载单例客户端；未配置角色时给出 2006（不建立连接） */
    private IAcsClient client() {
        IAcsClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    if (!ossProperties.stsConfigured() || !ossProperties.configured()) {
                        throw new BusinessException(Code.STORAGE_OSS_UNAVAILABLE, "对象存储暂不可用");
                    }
                    DefaultProfile profile = DefaultProfile.getProfile(
                            regionOf(ossProperties.getEndpoint()),
                            ossProperties.getAccessKeyId(),
                            ossProperties.getAccessKeySecret());
                    c = new DefaultAcsClient(profile);
                    client = c;
                }
            }
        }
        return c;
    }

    /** 从 OSS endpoint 推区域（{@code oss-cn-hangzhou.aliyuncs.com} → {@code cn-hangzhou}）；失败回落默认 */
    static String regionOf(String endpoint) {
        if (endpoint != null && endpoint.startsWith("oss-") && endpoint.contains(".")) {
            String first = endpoint.substring(0, endpoint.indexOf('.'));
            return first.substring("oss-".length());
        }
        return "cn-hangzhou";
    }

    private BusinessException ossUnavailable(String detail, Throwable cause) {
        log.warn("[STS] {}（已按 E-02 隔离为 2006）", detail);
        return new BusinessException(Code.STORAGE_OSS_UNAVAILABLE, "对象存储暂不可用");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** JSON 字符串转义：桶名按阿里云规则不含引号/反斜杠，此处只做最小防御 */
    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
