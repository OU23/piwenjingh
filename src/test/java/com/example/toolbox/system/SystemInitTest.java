package com.example.toolbox.system;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.auth.service.AuthService;
import com.example.toolbox.auth.vo.LoginVO;
import com.example.toolbox.common.constant.PermissionDict;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.SettingKey;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.setting.entity.SettingDO;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.system.dto.InitDTO;
import com.example.toolbox.system.service.SystemService;
import com.example.toolbox.tag.entity.TagDO;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.entity.UserPermissionDO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统初始化（验收 A1 / A2 / A3 / C1 / C5）。
 * 覆盖：未初始化判定、初始化后内容齐备（四件事）、可登录、重复调用 1007、失败不留半初始化状态。
 */
class SystemInitTest extends IntegrationTestBase {

    @Autowired
    private SystemService systemService;
    @Autowired
    private AuthService authService;

    private InitDTO initDto() {
        InitDTO dto = new InitDTO();
        dto.setAdminUsername("admin");
        dto.setAdminPassword("admin123456");
        dto.setAdminRealName("系统管理员");
        return dto;
    }

    @Test
    void notInitializedWhenNoUserExists() {
        assertFalse(systemService.isInitialized(), "空库应判定为未初始化");
    }

    /** A1 + A2：初始化后四件事齐备，且能用新管理员登录 */
    @Test
    void initCreatesAllFourThingsAndAdminCanLogin() {
        systemService.init(initDto());

        assertTrue(systemService.isInitialized(), "初始化后应判定为已初始化");

        // ① 管理员
        UserDO admin = userMapper.selectOne(
                Wrappers.<UserDO>lambdaQuery().eq(UserDO::getUsername, "admin"));
        assertNotNull(admin, "应创建管理员账号");
        assertEquals("系统管理员", admin.getRealName());
        assertFalse(admin.getPassword().contains("admin123456"),
                "密码必须以摘要存储，不得出现明文");

        // ② 全部 8 个权限点
        Set<String> granted = userServicePermissionCodes(admin.getId());
        assertEquals(Set.copyOf(PermissionDict.allCodes()), granted,
                "管理员应拥有全部权限点（8 个）");
        assertEquals(8, PermissionDict.allCodes().size(), "权限点字典应为 8 个");

        // ③ 默认部门，且管理员归入其中
        DeptDO dept = deptMapper.selectById(admin.getDeptId());
        assertNotNull(dept, "管理员应归入默认部门");
        assertEquals(SystemService.DEFAULT_DEPT_NAME, dept.getName());

        // ④ 基础标签字典
        long tagCount = tagMapper.selectCount(null);
        assertTrue(tagCount >= 5, "基础标签应写入（建议 5-8 个），实际 " + tagCount);

        // ⑤ 默认全局设置：存储根路径 + 单文件上限
        assertEquals(2, settingMapper.selectCount(null), "应写入 2 条默认全局设置");
        assertNotNull(settingValue(SettingKey.STORAGE_ROOT), "应写入存储根路径");
        assertNotNull(settingValue(SettingKey.MAX_FILE_SIZE), "应写入单文件大小上限");

        // ⑥ 可用新管理员账号登录
        LoginVO login = authService.login("admin", "admin123456", false);
        assertNotNull(login.getToken(), "登录应返回令牌");
        assertEquals("admin", login.getUser().getUsername());
        assertTrue(login.getUser().getPermissions().contains(PermissionCode.SYS_SETTING),
                "登录信息应带出全局设置权限");
    }

    /** A3 / C1：重复初始化 → 1007，且不重复写入数据 */
    @Test
    void secondInitRejectedAndNoDuplicateData() {
        systemService.init(initDto());
        long userCountAfterFirst = userMapper.selectCount(null);
        long deptCountAfterFirst = deptMapper.selectCount(null);
        long tagCountAfterFirst = tagMapper.selectCount(null);
        long settingCountAfterFirst = settingMapper.selectCount(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> systemService.init(initDto()));
        assertEquals(Code.ALREADY_INITIALIZED, ex.getCode(),
                "重复初始化应返回 1007");

        assertEquals(userCountAfterFirst, userMapper.selectCount(null), "不应重复创建用户");
        assertEquals(deptCountAfterFirst, deptMapper.selectCount(null), "不应重复创建部门");
        assertEquals(tagCountAfterFirst, tagMapper.selectCount(null), "不应重复写入标签");
        assertEquals(settingCountAfterFirst, settingMapper.selectCount(null), "不应重复写入设置");
    }

    /**
     * A3：失败不留半初始化状态。
     * 失败注入：传入非法账号（不满足 4-32 位字母数字下划线）使 UserService 校验失败，
     * 但 DTO 在 Controller 层才做 Bean Validation，Service 层可绕过 —— 因此改用
     * "部门已存在同名" 之外的路径：先手工占用 DEFAULT_DEPT_NAME 触发唯一键冲突。
     * 断言：事务回滚后库里无任何残留。
     */
    @Test
    void failedInitLeavesNoPartialState() {
        // 预置一个同名部门，使 init 内 deptMapper.insert 触发唯一键冲突
        DeptDO existing = new DeptDO();
        existing.setName(SystemService.DEFAULT_DEPT_NAME);
        existing.setStatus(1);
        existing.setCreateTime(java.time.LocalDateTime.now());
        deptMapper.insert(existing);

        long deptCountBefore = deptMapper.selectCount(null);

        assertThrows(Exception.class, () -> systemService.init(initDto()),
                "默认部门重名时初始化应失败");

        // 事务回滚：不得留下用户 / 权限 / 标签 / 设置
        assertEquals(0, userMapper.selectCount(null), "失败不得留下用户");
        assertEquals(0, userPermissionMapper.selectCount(null), "失败不得留下权限点");
        assertEquals(0, tagMapper.selectCount(null), "失败不得留下标签");
        assertEquals(0, settingMapper.selectCount(null), "失败不得留下设置");
        assertEquals(deptCountBefore, deptMapper.selectCount(null), "部门数应保持预置值");
        assertFalse(systemService.isInitialized(), "失败后仍应判定为未初始化");
    }

    // ---------------- 辅助 ----------------

    private Set<String> userServicePermissionCodes(Long userId) {
        List<UserPermissionDO> list = userPermissionMapper.selectList(
                Wrappers.<UserPermissionDO>lambdaQuery().eq(UserPermissionDO::getUserId, userId));
        return list.stream().map(UserPermissionDO::getPermissionCode)
                .collect(java.util.stream.Collectors.toSet());
    }

    private String settingValue(String key) {
        SettingDO s = settingMapper.selectOne(
                Wrappers.<SettingDO>lambdaQuery().eq(SettingDO::getSettingKey, key));
        return s == null ? null : s.getSettingValue();
    }

    /** 未初始化时不应存在任何用户，登录必须失败（避免空库被误认为已就绪） */
    @Test
    void loginFailsOnEmptyDatabase() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login("admin", "admin123456", false));
        assertEquals(Code.BAD_CREDENTIALS, ex.getCode(),
                "空库登录应返回 1006（不暴露系统未初始化）");
    }

    /** 标签字典内容应落在建议范围内（Q-11：5-8 个） */
    @Test
    void defaultTagsWithinSuggestedRange() {
        systemService.init(initDto());
        List<TagDO> tags = tagMapper.selectList(null);
        assertTrue(tags.size() >= 5 && tags.size() <= 8,
                "默认标签应为 5-8 个，实际 " + tags.size());
    }
}
