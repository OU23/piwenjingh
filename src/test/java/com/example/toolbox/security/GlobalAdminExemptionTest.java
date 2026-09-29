package com.example.toolbox.security;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.user.dto.UserSaveDTO;
import com.example.toolbox.user.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局管理员权限豁免 + 权限变更的豁免规则（M-21 / 契约 §6.1 · §9.4 · §3.3 v1.12）。
 *
 * <p>覆盖验收 <b>A4 / A5 / A7 / C7 / C8</b>：
 * <ul>
 *   <li>持 `sys:setting` = 全局管理员 → **不受权限点限制**（判权恒通过）；</li>
 *   <li>权限变更时：目标为**全局管理员**或**当前操作人** → 不推送、不下线；</li>
 *   <li>停用账号也可正常保存权限（既有行为不变）。</li>
 * </ul>
 */
@AutoConfigureMockMvc
class GlobalAdminExemptionTest extends IntegrationTestBase {

    private static final String PWD = "pass123456";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserService userService;
    @Autowired
    private PermissionChecker permissionChecker;
    @Autowired
    private ObjectMapper objectMapper;

    private Long deptId;

    @BeforeEach
    void setUpFixture() {
        DeptDO d = new DeptDO();
        d.setName("豁免测试部");
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);
        deptId = d.getId();
    }

    // ---------------- helpers ----------------

    private Long createUser(String username, List<String> perms) {
        loginAs(999_971L, "fixture", deptId, PermissionCode.USER_MANAGE, PermissionCode.USER_GRANT);
        UserSaveDTO dto = new UserSaveDTO();
        dto.setUsername(username);
        dto.setRealName("测试账号");
        dto.setPassword(PWD);
        dto.setDeptId(String.valueOf(deptId));
        dto.setPermissions(perms);
        return Long.valueOf(userService.create(dto));
    }

    private String login(String username) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PWD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        assertEquals(0, node.get("code").asInt(), "登录应成功：" + res.getResponse().getContentAsString());
        return node.get("data").get("token").asText();
    }

    private void updatePermissions(Long targetId, List<String> perms, String token) throws Exception {
        mockMvc.perform(put("/api/users/" + targetId + "/permissions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("permissions", perms))))
                .andExpect(status().isOk());
    }

    private void assertTokenRejected(String token) throws Exception {
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private void assertTokenWorks(String token) throws Exception {
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ---------------- A7：全局管理员不受权限点限制 ----------------

    /** A7：持 `sys:setting` → 任意权限点判定恒 true（与契约 v1.12 一致） */
    @Test
    void globalAdminBypassesEveryPermissionPoint() {
        LoginUser admin = LoginUser.builder()
                .userId(1L).username("admin").deptId(deptId)
                .permissions(Set.of(PermissionCode.SYS_SETTING))
                .build();

        assertTrue(permissionChecker.hasPermission(admin, PermissionCode.TOOL_UPLOAD));
        assertTrue(permissionChecker.hasPermission(admin, PermissionCode.USER_MANAGE));
        assertTrue(permissionChecker.hasPermission(admin, PermissionCode.DEPT_MANAGE));
        assertTrue(permissionChecker.hasPermission(admin, PermissionCode.TOOL_DELETE));
        // 说明：**部门隔离**豁免（N-04）由既有 ScopeCheckerTest 覆盖，此处只验"权限点豁免"
    }

    /** A7 反向：非全局管理员仍严格按权限点判定（回归，豁免不得外溢） */
    @Test
    void ordinaryUserStillCheckedByPermissionPoints() {
        LoginUser user = LoginUser.builder()
                .userId(2L).username("u").deptId(deptId)
                .permissions(Set.of(PermissionCode.TOOL_DOWNLOAD))
                .build();

        assertTrue(permissionChecker.hasPermission(user, PermissionCode.TOOL_DOWNLOAD));
        assertFalse(permissionChecker.hasPermission(user, PermissionCode.TOOL_UPLOAD));
        assertFalse(permissionChecker.hasPermission(user, PermissionCode.USER_MANAGE));
    }

    /** A7：isGlobalAdmin 口径 = 持 sys:setting */
    @Test
    void isGlobalAdminFollowsSysSetting() {
        Long adminId = createUser("g-admin", List.of(PermissionCode.SYS_SETTING));
        Long normalId = createUser("g-normal", List.of(PermissionCode.TOOL_UPLOAD));

        assertTrue(userService.isGlobalAdmin(adminId));
        assertFalse(userService.isGlobalAdmin(normalId));
    }

    // ---------------- A4 / C8：目标为全局管理员 → 不踢 ----------------

    /** A4 / C8：改**全局管理员**的权限 → 不推送、不下线（其旧令牌仍有效） */
    @Test
    void globalAdminTargetIsNotKicked() throws Exception {
        // 操作人：持 user:grant 的非全局管理员
        Long operatorId = createUser("op", List.of(PermissionCode.USER_GRANT));
        String operatorToken = login("op");

        // 目标：全局管理员，且在线
        Long targetId = createUser("g-target", List.of(PermissionCode.SYS_SETTING, PermissionCode.TOOL_DOWNLOAD));
        String targetToken = login("g-target");

        // 裁掉其 tool:download（权限集合确实变化了）
        updatePermissions(targetId, List.of(PermissionCode.SYS_SETTING), operatorToken);

        // 豁免：不下线
        assertTokenWorks(targetToken);
    }

    // ---------------- A5：目标为当前操作人 → 不踢 ----------------

    /** A5：管理员改**自己**的权限 → 不自我打断（旧令牌仍有效） */
    @Test
    void selfChangeDoesNotKickOperator() throws Exception {
        // 一个有 user:grant 的账号，用它自己的令牌改自己的权限
        Long selfId = createUser("self-op",
                List.of(PermissionCode.USER_GRANT, PermissionCode.TOOL_DOWNLOAD));
        String selfToken = login("self-op");

        updatePermissions(selfId, List.of(PermissionCode.USER_GRANT), selfToken);

        assertTokenWorks(selfToken);
    }

    // ---------------- C7：停用账号也能保存权限 ----------------

    /** C7：目标账号处于停用状态 → 保存权限照常成功（不因停用报错） */
    @Test
    void disabledTargetCanStillSavePermissions() throws Exception {
        Long operatorId = createUser("op2", List.of(PermissionCode.USER_GRANT));
        String operatorToken = login("op2");

        Long targetId = createUser("disabled-one", List.of(PermissionCode.TOOL_DOWNLOAD));
        // 直接置为停用（沿用既有字段口径）
        var target = userMapper.selectById(targetId);
        target.setStatus(StatusValue.DISABLED);
        userMapper.updateById(target);

        updatePermissions(targetId, List.of(PermissionCode.TOOL_UPLOAD), operatorToken);

        assertEquals(List.of(PermissionCode.TOOL_UPLOAD), userService.getPermissions(targetId));
    }

    /** 反向：普通账号被改权限 → **必须**下线（豁免不得误伤普通账号） */
    @Test
    void ordinaryTargetIsKicked() throws Exception {
        Long operatorId = createUser("op3", List.of(PermissionCode.USER_GRANT));
        String operatorToken = login("op3");

        Long targetId = createUser("plain", List.of(PermissionCode.TOOL_DOWNLOAD));
        String targetToken = login("plain");

        updatePermissions(targetId, List.of(PermissionCode.TOOL_UPLOAD), operatorToken);

        assertTokenRejected(targetToken);
    }
}
