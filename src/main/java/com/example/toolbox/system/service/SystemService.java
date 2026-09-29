package com.example.toolbox.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.constant.PermissionDict;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.dept.mapper.DeptMapper;
import com.example.toolbox.setting.service.SettingService;
import com.example.toolbox.system.dto.InitDTO;
import com.example.toolbox.tag.entity.TagDO;
import com.example.toolbox.tag.mapper.TagMapper;
import com.example.toolbox.user.dto.UserSaveDTO;
import com.example.toolbox.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系统初始化（契约 §十一）：仅在系统未初始化时可用；重复调用 → 1007。
 * 一次完成四件事：建首个全局管理员（全部权限点）+ 默认部门并归入 + 基础标签字典 + 默认全局设置。
 * 全程单事务：任一步失败不得留下半初始化状态（验收 A3 / C1）。
 */
@Service
@RequiredArgsConstructor
public class SystemService {

    /** 默认部门名称（待确认清单 Q-12 建议值） */
    public static final String DEFAULT_DEPT_NAME = "默认部门";

    /** 基础标签字典（待确认清单 Q-11 建议值：5-8 个通用标签） */
    private static final List<String> DEFAULT_TAGS = List.of(
            "数据处理", "网络工具", "系统运维", "开发调试", "效率工具", "文档资料");

    private final UserService userService;
    private final DeptMapper deptMapper;
    private final TagMapper tagMapper;
    private final SettingService settingService;
    private final AuditLogger auditLogger;

    /** 默认存储根路径：取自配置，未配置时用工作目录下 toolbox-files */
    @Value("${toolbox.storage.root:}")
    private String configuredStorageRoot;

    /** 系统是否已初始化：以"是否存在任一用户"为判据 */
    public boolean isInitialized() {
        return userService.anyUserExists();
    }

    @Transactional
    public void init(InitDTO dto) {
        // 幂等守卫：已初始化直接拒绝，保证并发下也只有一次成功（验收 C5 / A3）
        if (isInitialized()) {
            throw new BusinessException(Code.ALREADY_INITIALIZED);
        }

        // 1) 默认部门
        DeptDO dept = new DeptDO();
        dept.setName(DEFAULT_DEPT_NAME);
        dept.setStatus(StatusValue.ENABLED);
        dept.setCreateTime(LocalDateTime.now());
        deptMapper.insert(dept);

        // 2) 首个全局管理员，归入默认部门，赋予全部权限点
        UserSaveDTO admin = new UserSaveDTO();
        admin.setUsername(dto.getAdminUsername());
        admin.setPassword(dto.getAdminPassword());
        admin.setRealName(dto.getAdminRealName());
        admin.setDeptId(String.valueOf(dept.getId()));
        admin.setPermissions(PermissionDict.allCodes());
        userService.create(admin);

        // 3) 基础标签字典（幂等：已存在则跳过）
        LocalDateTime now = LocalDateTime.now();
        for (String name : DEFAULT_TAGS) {
            boolean exists = tagMapper.selectCount(
                    Wrappers.<TagDO>lambdaQuery().eq(TagDO::getName, name)) > 0;
            if (!exists) {
                TagDO tag = new TagDO();
                tag.setName(name);
                tag.setCreateTime(now);
                tagMapper.insert(tag);
            }
        }

        // 4) 默认全局设置（存储根路径 + 单文件上限 10GB）
        settingService.initDefaults(resolveStorageRoot());

        // 初始化属最高权限写操作：无登录上下文，操作人记为初始管理员账号
        auditLogger.write(dto.getAdminUsername(), "SYSTEM_INIT", "dept:" + dept.getId(), "OK");
    }

    /** 存储根路径：配置优先；未配置时落到工作目录下 toolbox-files，保证初始化可完成 */
    private String resolveStorageRoot() {
        if (configuredStorageRoot != null && !configuredStorageRoot.isBlank()) {
            return configuredStorageRoot;
        }
        return java.nio.file.Paths.get(System.getProperty("user.dir"), "toolbox-files")
                .toAbsolutePath().toString();
    }
}
