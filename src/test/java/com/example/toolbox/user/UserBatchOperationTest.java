package com.example.toolbox.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.entity.UserPermissionDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P13 / 契约 v1.16：**用户批量操作**（接口层，走完整过滤链）。
 *
 * <p>覆盖：批量停用生效 + 踢会话（A7）、覆盖式权限（A8）、忽略不存在 ID 且不整体回滚（A9/A11）、
 * 系统初始化管理员整体拒绝（A10，且**其他目标不被改动**）、非法入参 1003（A13）、权限点 403（A12）。
 *
 * <p>「系统初始化管理员」= 持 `sys:setting` 且 `user_id` 最小者（见 `AdminIdentityService`），
 * 故夹具**先插管理员、后插普通账号**，保证其 id 最小。
 */
@AutoConfigureMockMvc
class UserBatchOperationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    private Long deptId;
    /** 系统初始化管理员（持 sys:setting 且 id 最小） */
    private Long systemAdminId;
    /** 操作人：同时持 user:manage + user:grant */
    private Long operatorId;
    /** 普通账号 A：初始持 tool:upload + tool:download（用于验证覆盖式会清掉多余权限） */
    private Long userA;
    /** 普通账号 B：初始仅 tool:download */
    private Long userB;

    @BeforeEach
    void setUpData() {
        deptId = insertDept();
        systemAdminId = insertUser("admin", PermissionCode.SYS_SETTING);
        operatorId = insertUser("op", PermissionCode.USER_MANAGE, PermissionCode.USER_GRANT);
        userA = insertUser("ua", PermissionCode.TOOL_UPLOAD, PermissionCode.TOOL_DOWNLOAD);
        userB = insertUser("ub", PermissionCode.TOOL_DOWNLOAD);
    }

    // ---------------- 权限点（A12） ----------------

    @Test
    void endpointsRequireProperPermission() throws Exception {
        // 未登录 → 真 401
        mockMvc.perform(post("/api/users/batch-status").contentType("application/json")
                .content("{\"userIds\":[\"1\"],\"status\":0}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/users/batch-permissions").contentType("application/json")
                .content("{\"userIds\":[\"1\"],\"permissions\":[]}")).andExpect(status().isUnauthorized());

        // 已登录但无权限 → 真 403
        String noPerm = token(userA);
        mockMvc.perform(post("/api/users/batch-status").header("Authorization", "Bearer " + noPerm)
                        .contentType("application/json").content("{\"userIds\":[\"1\"],\"status\":0}"))
                .andExpect(status().isForbidden());
        // user:manage ≠ user:grant：批量权限需 user:grant
        String manageOnly = token(operatorId, PermissionCode.USER_MANAGE);
        mockMvc.perform(post("/api/users/batch-permissions").header("Authorization", "Bearer " + manageOnly)
                        .contentType("application/json").content("{\"userIds\":[\"1\"],\"permissions\":[]}"))
                .andExpect(status().isForbidden());
    }

    // ---------------- 批量停用（A7 / A9 / A11） ----------------

    @Test
    void batchDisableUpdatesStatusAndKicksSession() throws Exception {
        sessionRegistry.establish(userA, "sid-ua");

        batchStatus(PermissionCode.USER_MANAGE, ids(userA, userB), 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertThat(statusOf(userA)).isEqualTo(StatusValue.DISABLED);
        assertThat(statusOf(userB)).isEqualTo(StatusValue.DISABLED);
        // 停用立即生效：旧会话被移除（M-18 联动，经单条 updateStatus 复用）
        assertThat(sessionRegistry.hasActive(userA)).isFalse();
    }

    @Test
    void batchStatusIgnoresUnknownIdAndDoesNotRollbackOthers() throws Exception {
        batchStatus(PermissionCode.USER_MANAGE, ids(userA, 9_999_999L), 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 不存在的 ID 被忽略，其余照常处理（无整体回滚）
        assertThat(statusOf(userA)).isEqualTo(StatusValue.DISABLED);
    }

    @Test
    void batchEnableIsAllowedAndIdempotent() throws Exception {
        disableDirectly(userA);

        batchStatus(PermissionCode.USER_MANAGE, ids(userA), 1)
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(userA)).isEqualTo(StatusValue.ENABLED);

        // 幂等：再次启用仍成功
        batchStatus(PermissionCode.USER_MANAGE, ids(userA), 1)
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------------- 批量权限（A8） ----------------

    @Test
    void batchPermissionsOverwritesToFinalSet() throws Exception {
        assertThat(permsOf(userA)).containsExactlyInAnyOrder(PermissionCode.TOOL_UPLOAD, PermissionCode.TOOL_DOWNLOAD);

        batchPermissions(PermissionCode.USER_GRANT, ids(userA), List.of(PermissionCode.TOOL_DOWNLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 覆盖铁证：入参未含的 tool:upload 被清除
        assertThat(permsOf(userA)).containsExactly(PermissionCode.TOOL_DOWNLOAD);
    }

    @Test
    void batchPermissionsKicksSessionWhenChanged() throws Exception {
        sessionRegistry.establish(userB, "sid-ub");

        batchPermissions(PermissionCode.USER_GRANT, ids(userB), List.of(PermissionCode.TOOL_UPLOAD))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(sessionRegistry.hasActive(userB)).isFalse();
    }

    @Test
    void batchPermissionsWithEmptyListClearsPermissions() throws Exception {
        batchPermissions(PermissionCode.USER_GRANT, ids(userA), List.of())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(permsOf(userA)).isEmpty();
    }

    // ---------------- 管理员保护（A10，整体拒绝） ----------------

    @Test
    void batchDisableRejectsSystemAdminAsWhole() throws Exception {
        // 管理员与普通账号同一批：必须**整体拒绝**，userA 不得被停用
        batchStatus(PermissionCode.USER_MANAGE, ids(userA, systemAdminId), 0)
                .andExpect(jsonPath("$.code").value(1005));

        assertThat(statusOf(systemAdminId)).isEqualTo(StatusValue.ENABLED);
        assertThat(statusOf(userA)).isEqualTo(StatusValue.ENABLED);
    }

    @Test
    void batchEnableSystemAdminIsAllowed() throws Exception {
        disableDirectly(systemAdminId);

        // 启用不受限（与单条 updateStatus 一致）
        batchStatus(PermissionCode.USER_MANAGE, ids(systemAdminId), 1)
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(systemAdminId)).isEqualTo(StatusValue.ENABLED);
    }

    @Test
    void batchPermissionsRejectsSystemAdminAsWhole() throws Exception {
        List<String> before = permsOf(systemAdminId);

        batchPermissions(PermissionCode.USER_GRANT, ids(userA, systemAdminId), List.of(PermissionCode.TOOL_DOWNLOAD))
                .andExpect(jsonPath("$.code").value(1005));

        // 管理员权限未被改动；且同一批的 userA 也未被改动（整体拒绝）
        assertThat(permsOf(systemAdminId)).containsExactlyInAnyOrderElementsOf(before);
        assertThat(permsOf(userA)).containsExactlyInAnyOrder(PermissionCode.TOOL_UPLOAD, PermissionCode.TOOL_DOWNLOAD);
    }

    // ---------------- 非法入参（A13） ----------------

    @Test
    void batchStatusInvalidStatusReturns1003() throws Exception {
        batchStatus(PermissionCode.USER_MANAGE, ids(userA), 5)
                .andExpect(jsonPath("$.code").value(1003));
        assertThat(statusOf(userA)).isEqualTo(StatusValue.ENABLED);
    }

    @Test
    void batchPermissionsInvalidCodeReturns1003() throws Exception {
        batchPermissions(PermissionCode.USER_GRANT, ids(userA), List.of("not:a:code"))
                .andExpect(jsonPath("$.code").value(1003));
        assertThat(permsOf(userA)).containsExactlyInAnyOrder(PermissionCode.TOOL_UPLOAD, PermissionCode.TOOL_DOWNLOAD);
    }

    @Test
    void batchStatusEmptyUserIdsReturns1003() throws Exception {
        batchStatus(PermissionCode.USER_MANAGE, "", 0)
                .andExpect(jsonPath("$.code").value(1003));
    }

    // ---------------- 夹具 ----------------

    private Long insertDept() {
        DeptDO d = new DeptDO();
        d.setName("研发部");
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);
        return d.getId();
    }

    private Long insertUser(String username, String... perms) {
        UserDO u = new UserDO();
        u.setUsername(username);
        u.setPassword("$2a$10$not-a-real-hash-but-shape-valid-placeholder");
        u.setRealName(username);
        u.setDeptId(deptId);
        u.setStatus(StatusValue.ENABLED);
        u.setCreateTime(LocalDateTime.now());
        userMapper.insert(u);
        for (String p : perms) {
            UserPermissionDO up = new UserPermissionDO();
            up.setUserId(u.getId());
            up.setPermissionCode(p);
            up.setCreateTime(LocalDateTime.now());
            userPermissionMapper.insert(up);
        }
        return u.getId();
    }

    private void disableDirectly(Long userId) {
        UserDO u = userMapper.selectById(userId);
        u.setStatus(StatusValue.DISABLED);
        userMapper.updateById(u);
    }

    private Integer statusOf(Long userId) {
        return userMapper.selectById(userId).getStatus();
    }

    private List<String> permsOf(Long userId) {
        return userPermissionMapper.selectList(
                        Wrappers.<UserPermissionDO>lambdaQuery().eq(UserPermissionDO::getUserId, userId))
                .stream().map(UserPermissionDO::getPermissionCode).toList();
    }

    private String token(Long userId, String... perms) {
        return signToken(LoginUser.builder()
                .userId(userId)
                .username("u" + userId)
                .realName("用户" + userId)
                .deptId(deptId)
                .permissions(Set.of(perms))
                .build());
    }

    private String ids(Long... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values[i]).append('"');
        }
        return sb.toString();
    }

    private ResultActions batchStatus(String perm, String rawIds, int status) throws Exception {
        return mockMvc.perform(post("/api/users/batch-status")
                .header("Authorization", "Bearer " + token(operatorId, perm))
                .contentType("application/json")
                .content("{\"userIds\":[" + rawIds + "],\"status\":" + status + "}"));
    }

    private ResultActions batchPermissions(String perm, String rawIds, List<String> codes) throws Exception {
        String quoted = codes.stream().map(c -> "\"" + c + "\"").reduce((a, b) -> a + "," + b).orElse("");
        return mockMvc.perform(post("/api/users/batch-permissions")
                .header("Authorization", "Bearer " + token(operatorId, perm))
                .contentType("application/json")
                .content("{\"userIds\":[" + rawIds + "],\"permissions\":[" + quoted + "]}"));
    }
}
