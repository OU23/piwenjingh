package com.example.toolbox.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.support.TestToolEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部门可见范围唯一入口（验收 A7）。
 *
 * <p>判定逻辑（canAccess / assertAccessible）是纯函数，直接实例化验证；
 * `applyVisibility` 需要 MyBatis-Plus 的实体 lambda 缓存（由容器启动时
 * Mapper 初始化触发），故整个用例跑在 Spring 上下文中。
 * 规则来源：契约 N-04 + security.md + 原则 R3。
 */
class ScopeCheckerTest extends IntegrationTestBase {

    @Autowired
    private ScopeChecker checker;

    /** 测试用资源：只暴露 scope / deptId 两个判定所需属性（无上传人） */
    private record FakeTool(String scope, Long deptId) implements ScopedResource {
        @Override
        public String getScope() {
            return scope;
        }

        @Override
        public Long getDeptId() {
            return deptId;
        }

        @Override
        public Long getId() {
            return null;
        }

        @Override
        public Long getUploaderId() {
            return null;
        }
    }

    /** 测试用资源：额外带上传人，用于 M-25「上传人恒可见」规则 */
    private record OwnedTool(String scope, Long deptId, Long uploaderId) implements ScopedResource {
        @Override
        public String getScope() {
            return scope;
        }

        @Override
        public Long getDeptId() {
            return deptId;
        }

        @Override
        public Long getId() {
            return 1L;
        }

        @Override
        public Long getUploaderId() {
            return uploaderId;
        }
    }

    private LoginUser user(Long deptId, String... permissions) {
        return LoginUser.builder()
                .userId(1L)
                .username("u")
                .deptId(deptId)
                .permissions(Set.of(permissions))
                .build();
    }

    /** 规则 1：PUBLIC 全员可见 */
    @Test
    void publicVisibleToEveryone() {
        ScopedResource pub = new FakeTool(Scope.PUBLIC, 100L);
        assertTrue(checker.canAccess(pub, user(200L)), "公共资源跨部门可见");
        assertTrue(checker.canAccess(pub, user(null)), "公共资源对无部门用户可见");
    }

    /** 规则 2：DEPT 仅同部门可见 */
    @Test
    void deptVisibleOnlyWithinSameDept() {
        ScopedResource deptTool = new FakeTool(Scope.DEPT, 100L);
        assertTrue(checker.canAccess(deptTool, user(100L)), "同部门可见");
        assertFalse(checker.canAccess(deptTool, user(200L)), "跨部门不可见");
    }

    /** 规则 3：全局管理员（持 sys:setting）不受部门隔离 */
    @Test
    void globalAdminBypassesDeptIsolation() {
        LoginUser admin = user(999L, PermissionCode.SYS_SETTING);
        assertTrue(checker.isGlobalAdmin(admin), "持 sys:setting 应判定为全局管理员");

        assertTrue(checker.canAccess(new FakeTool(Scope.DEPT, 100L), admin),
                "全局管理员可见其他部门的 DEPT 资源");
        assertTrue(checker.canAccess(new FakeTool(Scope.PUBLIC, 100L), admin),
                "全局管理员当然可见公共资源");
    }

    /** 非全局管理员不得越权：持其他权限点无效 */
    @Test
    void nonAdminWithOtherPermissionsIsStillIsolated() {
        LoginUser normal = user(200L, PermissionCode.TOOL_DOWNLOAD, PermissionCode.USER_MANAGE);
        assertFalse(checker.isGlobalAdmin(normal), "无 sys:setting 不得判定为全局管理员");
        assertFalse(checker.canAccess(new FakeTool(Scope.DEPT, 100L), normal),
                "非管理员跨部门仍不可见");
    }

    /** 规则 4：无部门用户只能看 PUBLIC */
    @Test
    void userWithoutDeptSeesOnlyPublic() {
        LoginUser noDept = user(null);
        assertTrue(checker.canAccess(new FakeTool(Scope.PUBLIC, 100L), noDept));
        assertFalse(checker.canAccess(new FakeTool(Scope.DEPT, 100L), noDept),
                "无部门用户不得看到任何 DEPT 资源");
    }

    /** 空值防御：资源或用户为 null 一律不可见 */
    @Test
    void nullResourceOrUserIsInvisible() {
        assertFalse(checker.canAccess(null, user(100L)), "资源不存在视为不可见");
        assertFalse(checker.canAccess(new FakeTool(Scope.PUBLIC, 100L), null), "未登录不可见");
        assertFalse(checker.isGlobalAdmin(null), "未登录不是管理员");
    }

    /** M-25 规则 2：持 tool:assign 可跨部门看到全部工具（仅工具域） */
    @Test
    void toolAssignSeesAllToolsAcrossDepts() {
        LoginUser assigner = user(200L, PermissionCode.TOOL_ASSIGN);
        assertTrue(checker.isToolAssign(assigner), "持 tool:assign 应判定为归属调整者");
        assertTrue(checker.canAccess(new FakeTool(Scope.DEPT, 100L), assigner),
                "持 tool:assign 可见其他部门的 DEPT 工具");
        assertFalse(checker.isGlobalAdmin(assigner), "tool:assign 不等于全局管理员（不影响其他域）");
    }

    /** M-25 规则 5：上传人恒可见（工具被改到别的部门后，上传人仍能看到 / 管理） */
    @Test
    void uploaderAlwaysSeesOwnTool() {
        ScopedResource movedToOtherDept = new OwnedTool(Scope.DEPT, 200L, 1L);
        LoginUser uploader = user(999L);   // 上传人现在在别的部门
        assertTrue(checker.canAccess(movedToOtherDept, uploader), "上传人恒可见自己上传的工具");

        LoginUser otherUser = LoginUser.builder()
                .userId(2L).username("other").deptId(300L).permissions(Set.of()).build();
        assertFalse(checker.canAccess(movedToOtherDept, otherUser), "非上传人跨部门仍不可见");
    }

    /** assertAccessible：不可见即抛 403（契约 §十二 → 1002） */
    @Test
    void assertAccessibleThrowsForbidden() {
        LoginUser outsider = user(200L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> checker.assertAccessible(new FakeTool(Scope.DEPT, 100L), outsider));
        assertEquals(Code.FORBIDDEN, ex.getCode(), "越权应返回 1002");

        // 可见时不抛
        checker.assertAccessible(new FakeTool(Scope.PUBLIC, 100L), outsider);
    }

    // ---------------- 列表过滤下推（SQL 层） ----------------

    /** 全局管理员：不加任何过滤 */
    @Test
    void applyVisibilityForAdminAddsNoFilter() {
        LoginUser admin = user(999L, PermissionCode.SYS_SETTING);
        LambdaQueryWrapper<TestToolEntity> w = checker.applyVisibility(
                new LambdaQueryWrapper<>(), TestToolEntity::getScope,
                TestToolEntity::getDeptId, TestToolEntity::getUploaderId, admin);
        assertEquals("", w.getTargetSql(), "全局管理员不应附加过滤条件");
    }

    /** 普通用户：过滤下推到 SQL —— "(scope = PUBLIC OR dept_id = ? OR uploader_id = ?)" */
    @Test
    void applyVisibilityForNormalUserPushesFilterToSql() {
        LambdaQueryWrapper<TestToolEntity> w = checker.applyVisibility(
                new LambdaQueryWrapper<>(), TestToolEntity::getScope,
                TestToolEntity::getDeptId, TestToolEntity::getUploaderId, user(100L));

        String sql = w.getTargetSql();
        assertTrue(sql.contains("scope"), "过滤条件应含 scope，实际：" + sql);
        assertTrue(sql.contains("dept_id"), "过滤条件应含 dept_id，实际：" + sql);
        assertTrue(sql.contains("uploader_id"), "过滤条件应含 uploader_id（M-25 上传人恒可见），实际：" + sql);

        // 参数应含 PUBLIC、当前用户部门与当前用户 ID
        List<Object> params = w.getParamNameValuePairs().values().stream().toList();
        assertTrue(params.contains(Scope.PUBLIC), "参数应含 PUBLIC，实际：" + params);
        assertTrue(params.contains(100L), "参数应含用户部门，实际：" + params);
    }

    /** 无部门用户：公共 + 自己上传（M-25：无部门的上传人也必须能看到自己的工具） */
    @Test
    void applyVisibilityForNoDeptUserKeepsPublicAndOwnTools() {
        LambdaQueryWrapper<TestToolEntity> w = checker.applyVisibility(
                new LambdaQueryWrapper<>(), TestToolEntity::getScope,
                TestToolEntity::getDeptId, TestToolEntity::getUploaderId, user(null));

        String sql = w.getTargetSql();
        assertFalse(sql.contains("dept_id"), "无部门用户不应出现部门条件，实际：" + sql);
        assertTrue(sql.contains("uploader_id"), "无部门用户仍须能看到自己上传的工具，实际：" + sql);
        List<Object> params = w.getParamNameValuePairs().values().stream().toList();
        assertTrue(params.contains(Scope.PUBLIC));
    }

    /** 持 tool:assign：不加任何过滤（跨部门看全部） */
    @Test
    void applyVisibilityForToolAssignAddsNoFilter() {
        LambdaQueryWrapper<TestToolEntity> w = checker.applyVisibility(
                new LambdaQueryWrapper<>(), TestToolEntity::getScope,
                TestToolEntity::getDeptId, TestToolEntity::getUploaderId,
                user(100L, PermissionCode.TOOL_ASSIGN));
        assertEquals("", w.getTargetSql(), "持 tool:assign 不应附加过滤条件");
    }

    /** 过滤条件可继续追加其他条件（组合性：B3 的标签 / 关键词筛选要能叠加） */
    @Test
    void applyVisibilityIsComposable() {
        LambdaQueryWrapper<TestToolEntity> w = new LambdaQueryWrapper<>();
        w.eq(TestToolEntity::getName, "demo");
        checker.applyVisibility(w, TestToolEntity::getScope, TestToolEntity::getDeptId,
                TestToolEntity::getUploaderId, user(100L));

        String sql = w.getTargetSql();
        assertTrue(sql.contains("name"), "应保留调用方追加的条件，实际：" + sql);
        assertTrue(sql.contains("scope"), "应叠加可见性条件，实际：" + sql);
    }

}
