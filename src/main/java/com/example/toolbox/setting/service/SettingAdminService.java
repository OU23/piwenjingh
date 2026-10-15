package com.example.toolbox.setting.service;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.SettingKey;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.util.PathUtils;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.PermissionChecker;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.setting.dto.SettingSaveDTO;
import com.example.toolbox.setting.vo.SettingVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 全局设置的读写业务（契约 §十）。底层键值读写见 {@link SettingService}。
 *
 * <p>三条硬口径：
 * <ol>
 *   <li><b>读</b>：登录即可，但 `storageRoot` **只对 `sys:setting` 权限者返回**（N-03）；</li>
 *   <li><b>写</b>：需 `sys:setting`；**至少提交一项**（都不传 → 1003，不静默成功）；</li>
 *   <li><b>路径校验</b>：存在性 + 可写性，不满足 → **2003 且说明原因**（不存在 / 不是目录 / 不可写），
 *       **不自动创建目录** —— 契约 §十 明确"按字符串校验其存在性与可写性"，
 *       自动建目录会把"管理员填错路径"变成"系统悄悄建了个错目录"，更难排查。</li>
 * </ol>
 *
 * <p>本类**只在事务内做纯 DB 写**（原则 8）：路径校验是"看"不是"写"，
 * 不做任何文件创建 / 删除，因此不会在事务里夹带文件 IO。
 */
@Service
@RequiredArgsConstructor
public class SettingAdminService {

    private final SettingService settingService;
    private final PermissionChecker permissionChecker;
    private final AuditLogger auditLogger;

    /** 读取全局设置（登录即可；`storageRoot` 按权限返回） */
    public SettingVO get() {
        LoginUser user = requireLogin();
        boolean canSeeRoot = permissionChecker.hasPermission(user, PermissionCode.SYS_SETTING);
        return SettingVO.builder()
                .storageRoot(canSeeRoot ? settingService.getStorageRoot() : null)
                .maxFileSize(settingService.getMaxFileSize())
                .chunkSize(settingService.getChunkSize())
                .build();
    }

    /** 保存全局设置（需 `sys:setting`） */
    @Transactional
    public void save(SettingSaveDTO dto) {
        if (dto == null || (dto.getStorageRoot() == null && dto.getMaxFileSize() == null && dto.getChunkSize() == null)) {
            throw new BusinessException(Code.PARAM_INVALID,
                    "至少需要提交一项设置（storageRoot / maxFileSize / chunkSize）");
        }
        if (dto.getStorageRoot() != null) {
            String root = PathUtils.normalizeSlashes(dto.getStorageRoot());
            if (root == null || root.isBlank()) {
                throw new BusinessException(Code.PARAM_INVALID, "存储根路径不能为空");
            }
            assertUsable(root);
            settingService.putValue(SettingKey.STORAGE_ROOT, root);
        }
        if (dto.getMaxFileSize() != null) {
            if (dto.getMaxFileSize() <= 0) {
                throw new BusinessException(Code.PARAM_INVALID, "单文件大小上限必须为正整数");
            }
            settingService.putValue(SettingKey.MAX_FILE_SIZE, String.valueOf(dto.getMaxFileSize()));
        }
        if (dto.getChunkSize() != null) {
            long chunk = dto.getChunkSize();
            if (chunk < SettingService.MIN_CHUNK_SIZE || chunk > SettingService.MAX_CHUNK_SIZE) {
                throw new BusinessException(Code.PARAM_INVALID,
                        "分片大小需在 " + mb(SettingService.MIN_CHUNK_SIZE) + " ~ "
                                + mb(SettingService.MAX_CHUNK_SIZE) + " MB 之间");
            }
            settingService.putValue(SettingKey.CHUNK_SIZE, String.valueOf(chunk));
        }
        auditLogger.write(currentOperator(), "SETTING_SAVE", "setting", "OK");
    }

    // ---------------- 内部 ----------------

    /**
     * 路径可用性校验：存在 + 是目录 + 可写。
     * 校验通过**不落盘任何东西**；失败给出**具体原因**（验收 C1：明确原因，不误报成功）。
     */
    private void assertUsable(String root) {
        Path path;
        try {
            path = Paths.get(root);
        } catch (RuntimeException e) {
            throw new BusinessException(Code.STORAGE_UNAVAILABLE, "存储路径格式非法：" + root);
        }
        if (!Files.exists(path)) {
            throw new BusinessException(Code.STORAGE_UNAVAILABLE, "存储路径不存在：" + root);
        }
        if (!Files.isDirectory(path)) {
            throw new BusinessException(Code.STORAGE_UNAVAILABLE, "存储路径不是目录：" + root);
        }
        if (!Files.isWritable(path)) {
            throw new BusinessException(Code.STORAGE_UNAVAILABLE, "存储路径不可写：" + root);
        }
    }

    /** 字节 → MB（仅用于错误提示文案） */
    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }

    private LoginUser requireLogin() {
        LoginUser user = UserContext.get();
        if (user == null) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }
        return user;
    }

    private String currentOperator() {
        LoginUser user = UserContext.get();
        return user == null ? null : user.getUsername();
    }
}
