package com.example.toolbox.upload.service;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.setting.service.SettingService;
import com.example.toolbox.storage.OssProperties;
import com.example.toolbox.storage.OssStorageService;
import com.example.toolbox.storage.StorageBackend;
import com.example.toolbox.storage.StorageProperties;
import com.example.toolbox.upload.dto.UploadDirectCompleteDTO;
import com.example.toolbox.upload.dto.UploadDirectCredentialDTO;
import com.example.toolbox.upload.vo.UploadCredentialVO;
import com.example.toolbox.upload.vo.UploadMergeVO;
import com.example.toolbox.upload.vo.UploadModeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OSS 直传编排（M-23 / B14 / 方案 §5.3 ~ §5.6）。
 *
 * <p>三个职责：① 上报上传模式与分片参数；② 签发直传凭证（含对象键分配 / 复用）；
 * ③ 直传完成后的**登记复核**（HEAD，不写业务表）。
 *
 * <p><b>与既有链路的关系</b>：`complete` 出参**复用 {@link UploadMergeVO}** —— 因此
 * 「创建工具 / 追加版本」（契约 §九）**一行不用改**，只是把 `fileKey` 的来源从 `merge` 换成 `complete`。
 *
 * <p><b>两条边界（硬）</b>：
 * <ul>
 *   <li>三个方法**均只读**（`complete` 只 HEAD + 返回），**不开事务**、**不读写文件内容**；</li>
 *   <li>直传**不经过** `StorageService`（其语义是"服务端读写"，而直传的文件从未到过服务端）；
 *       HEAD 复核直接复用 {@link OssStorageService}（对象必然在 OSS）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadDirectService {

    /** 对象键前缀 */
    public static final String KEY_PREFIX = "tools/";

    /** OSS 分片上传单对象分片数上限 */
    public static final int MAX_PARTS = 10_000;

    private static final long MB = 1024L * 1024L;

    /** 对象键形态：{@code tools/<deptId>/<32位小写hex><可选.ext>} */
    private static final Pattern OBJECT_KEY =
            Pattern.compile("^tools/(\\d+)/([0-9a-f]{32})(\\.[a-z0-9]{1,10})?$");

    private final StorageProperties storageProperties;
    private final OssProperties ossProperties;
    private final SettingService settingService;
    private final OssStsCredentialIssuer credentialIssuer;
    private final OssStorageService ossStorageService;
    private final AuditLogger auditLogger;

    // ---------------- ① 模式与分片参数 ----------------

    /** 当前上传通道与分片参数（分流依据 = **全局开关**，不是单文件落点） */
    public UploadModeVO mode() {
        boolean direct = storageProperties.writeBackend() == StorageBackend.OSS;
        return UploadModeVO.builder()
                .mode(direct ? "direct" : "server")
                .partSize(settingService.getChunkSize())
                .partSizeMin(SettingService.MIN_CHUNK_SIZE)
                .partSizeMax(SettingService.MAX_CHUNK_SIZE)
                .maxFileSize(settingService.getMaxFileSize())
                .build();
    }

    // ---------------- ② 签发凭证 ----------------

    /** 签发直传临时凭证（含对象键分配 / 复用 与 partSize 自动上调） */
    public UploadCredentialVO credentials(UploadDirectCredentialDTO dto) {
        LoginUser user = currentUser();
        Long deptId = requireDept(user);

        long fileSize = dto.getFileSize() == null ? 0L : dto.getFileSize();
        if (fileSize <= 0) {
            throw new BusinessException(Code.PARAM_INVALID, "文件大小必须为正数");
        }
        long maxFileSize = settingService.getMaxFileSize();
        if (fileSize > maxFileSize) {
            throw new BusinessException(Code.FILE_TOO_LARGE,
                    "文件超过大小上限（当前上限 " + maxFileSize + " 字节）");
        }

        String objectKey = resolveObjectKey(deptId, dto.getObjectKey(), dto.getFileName());
        long partSize = resolvePartSize(fileSize);

        // 凭证签发在**鉴权与上限校验之后**（方案 §2.3 硬约束 1）
        StsCredential credential = credentialIssuer.issue(user.getUserId(), deptId);

        auditLogger.write(user.getUsername(), "UPLOAD_DIRECT_CREDENTIAL", "key:" + objectKey, "OK");

        return UploadCredentialVO.builder()
                .objectKey(objectKey)
                .region(OssStsCredentialIssuer.regionOf(ossProperties.getEndpoint()))
                .endpoint(ossProperties.getEndpoint())
                .bucket(ossProperties.getBucket())
                .credentials(UploadCredentialVO.Credentials.builder()
                        .accessKeyId(credential.accessKeyId())
                        .accessKeySecret(credential.accessKeySecret())
                        .securityToken(credential.securityToken())
                        .build())
                .expiration(credential.expiration())
                .partSize(partSize)
                .maxParts(MAX_PARTS)
                .build();
    }

    // ---------------- ③ 完成登记复核 ----------------

    /** 直传完成后登记复核：前缀校验 → HEAD（存在 + 大小）→ 返回 {@link UploadMergeVO}（**不写任何业务表**） */
    public UploadMergeVO complete(UploadDirectCompleteDTO dto) {
        LoginUser user = currentUser();
        Long deptId = requireDept(user);

        assertOwnedObjectKey(dto.getObjectKey(), deptId);

        long declared = dto.getFileSize() == null ? -1L : dto.getFileSize();
        long maxFileSize = settingService.getMaxFileSize();
        if (declared > maxFileSize) {
            throw new BusinessException(Code.FILE_TOO_LARGE,
                    "文件超过大小上限（当前上限 " + maxFileSize + " 字节）");
        }

        long actual = headSize(dto.getObjectKey());

        if (declared >= 0 && actual != declared) {
            // 声明与实际不符：删除对象回收空间，并报 2002（方案 §4.4）
            safeDelete(dto.getObjectKey());
            throw new BusinessException(Code.CHUNK_INVALID,
                    "对象大小与声明不一致（实际 " + actual + " 字节，声明 " + declared + " 字节）");
        }

        auditLogger.write(user.getUsername(), "UPLOAD_DIRECT_COMPLETE", "fileKey:" + dto.getObjectKey(), "OK");

        return UploadMergeVO.builder()
                .fileKey(dto.getObjectKey())
                .fileName(dto.getFileName())
                .fileSize(actual)
                .build();
    }

    // ---------------- 内部 ----------------

    /** HEAD 对象取大小；不存在 → 2002（上传未完成）；OSS 异常 → 2006 */
    private long headSize(String objectKey) {
        try {
            if (!ossStorageService.exists(objectKey)) {
                throw new BusinessException(Code.CHUNK_INVALID, "上传未完成：对象不存在");
            }
            return ossStorageService.size(objectKey);
        } catch (NoSuchFileException e) {
            throw new BusinessException(Code.CHUNK_INVALID, "上传未完成：对象不存在");
        } catch (IOException e) {
            throw new BusinessException(Code.STORAGE_OSS_UNAVAILABLE, "对象存储暂不可用");
        }
    }

    /** 删除失败不影响主流程（不阻断报错返回） */
    private void safeDelete(String objectKey) {
        try {
            ossStorageService.delete(objectKey);
        } catch (IOException | RuntimeException e) {
            log.warn("[DIRECT] 清理大小不符的对象失败 key={} err={}", objectKey, e.getMessage());
        }
    }

    /** 对象键：入参带值时校验并**原样复用**（续传），否则新建 */
    private String resolveObjectKey(Long deptId, String incoming, String fileName) {
        if (incoming != null && !incoming.isBlank()) {
            assertOwnedObjectKey(incoming, deptId);
            return incoming;
        }
        return KEY_PREFIX + deptId + "/" + UUID.randomUUID().toString().replace("-", "") + extOf(fileName);
    }

    /** 前缀 + 形态双校验：非本人部门前缀 / 形态不符 → 1003 */
    private void assertOwnedObjectKey(String objectKey, Long deptId) {
        Matcher matcher = OBJECT_KEY.matcher(objectKey == null ? "" : objectKey);
        if (!matcher.matches() || !matcher.group(1).equals(String.valueOf(deptId))) {
            throw new BusinessException(Code.PARAM_INVALID, "对象键非法或不属于当前部门");
        }
    }

    /**
     * 生效分片大小：默认取设置值；若按设置值切分将超过 {@link #MAX_PARTS} 片，则**自动上调**
     * （向上取整到 1 MB）并回显，**不回写设置**（方案 §4.3）。
     */
    long resolvePartSize(long fileSize) {
        long base = settingService.getChunkSize();
        long needed = ceilDiv(fileSize, MAX_PARTS);
        if (needed <= base) {
            return base;
        }
        long adjusted = ceilDiv(needed, MB) * MB;
        if (adjusted > SettingService.MAX_CHUNK_SIZE) {
            throw new BusinessException(Code.PARAM_INVALID,
                    "文件过大，超出分片上传上限（" + MAX_PARTS + " 片）；请先在「全局设置」调大分片大小");
        }
        return adjusted;
    }

    /** 扩展名白名单化：小写、长度 ≤ 10、仅 {@code [a-z0-9]}；不符则丢弃 */
    static String extOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (ext.length() > 10) {
            return "";
        }
        for (int i = 0; i < ext.length(); i++) {
            char ch = ext.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
            if (!ok) {
                return "";
            }
        }
        return "." + ext;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    private LoginUser currentUser() {
        LoginUser user = UserContext.get();
        if (user == null) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }
        return user;
    }

    private Long requireDept(LoginUser user) {
        if (user.getDeptId() == null) {
            throw new BusinessException(Code.PARAM_INVALID, "当前账号未分配部门，无法直传");
        }
        return user.getDeptId();
    }
}
