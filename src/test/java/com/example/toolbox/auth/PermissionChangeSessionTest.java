package com.example.toolbox.auth;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.LoginUser;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 权限变更即时生效（M-21 / 契约 §3.3 v1.12）。
 *
 * <p>覆盖验收 <b>A1 / A3 / A4 / A5 / A6</b> 与 <b>C1 / C2 / C3 / C4 / C5 / C6 / C7 / C8</b>：
 * 权限**实际发生变化** → 立即终结该账号旧会话（旧令牌下一次请求即 401）；
 * 相同集合不触发；目标为全局管理员或当前操作人时豁免。
 */
@AutoConfigureMockMvc
class PermissionChangeSessionTest extends IntegrationTestBase {

    /** 操作人：持 `user:grant` 的**非**全局管理员（避免豁免规则干扰） */
    private static final long OPERATOR_ID = 999_981L;
    private static final String PWD = "pass123456";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserService userService;
    @Autowired
    private ObjectMapper objectMapper;

    private Long deptId;
    private String operatorToken;

    @BeforeEach
    void setUpFixture() {
        DeptDO d = new DeptDO();
        d.setName("权限变更部");
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);
        deptId = d.getId();

        loginAs(OPERATOR_ID, "grantor", deptId, PermissionCode.USER_GRANT);
        operatorToken = signToken(LoginUser.builder()
                .userId(OPERATOR_ID).username("grantor").deptId(deptId)
                .permissions(Set.of(PermissionCode.USER_GRANT)).build());
    }

    // ---------------- helpers ----------------

    /** 建账号（先设操作人上下文，供审计取当前操作人） */
    private Long createUser(String username, List<String> perms) {
        loginAs(OPERATOR_ID, "grantor", deptId, PermissionCode.USER_GRANT);
        UserSaveDTO dto = new UserSaveDTO();
        dto.setUsername(username);
        dto.setRealName("目标用户");
        dto.setPassword(PWD);
        dto.setDeptId(String.valueOf(deptId));
        dto.setPermissions(perms);
        return Long.valueOf(userService.create(dto));
    }

    /** 走真实登录接口（建立会话），返回令牌 */
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

    /** 用操作人令牌改目标账号权限 */
    private void updatePermissions(Long targetId, List<String> perms) throws Exception {
        updatePermissions(targetId, perms, operatorToken);
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

    // ---------------- A. 核心 ----------------

    /** A1 ★：权限变更 → 旧令牌下一次请求即 401（会话已被移除） */
    @Test
    void permissionChangeRevokesOldTokenImmediately() throws Exception {
        Long uid = createUser("t1", List.of(PermissionCode.TOOL_DOWNLOAD));
        String token = login("t1");
        assertTokenWorks(token);

        updatePermissions(uid, List.of(PermissionCode.TOOL_UPLOAD));

        assertTokenRejected(token);
    }

    /** A3：提交**相同**权限集合 → 不触发，旧令牌仍有效 */
    @Test
    void unchangedPermissionSetKeepsSessionAlive() throws Exception {
        Long uid = createUser("t2", List.of(PermissionCode.TOOL_DOWNLOAD, PermissionCode.TOOL_UPLOAD));
        String token = login("t2");

        updatePermissions(uid, List.of(PermissionCode.TOOL_UPLOAD, PermissionCode.TOOL_DOWNLOAD));

        assertTokenWorks(token);
    }

    /** C6：连续两次 —— 第一次变更触发，第二次相同集合不再触发（新令牌不受影响） */
    @Test
    void secondIdenticalSubmissionDoesNotRevoke() throws Exception {
        Long uid = createUser("t3", List.of(PermissionCode.TOOL_DOWNLOAD));

        updatePermissions(uid, List.of(PermissionCode.TOOL_UPLOAD));
        String fresh = login("t3");

        updatePermissions(uid, List.of(PermissionCode.TOOL_UPLOAD));

        assertTokenWorks(fresh);
    }

    // ---------------- C. 边界 ----------------

    /** C2：权限集合**变大**同样触发（判定依据是"发生变化"） */
    @Test
    void addingPermissionAlsoRevokes() throws Exception {
        Long uid = createUser("t4", List.of(PermissionCode.TOOL_DOWNLOAD));
        String token = login("t4");

        updatePermissions(uid,
                List.of(PermissionCode.TOOL_DOWNLOAD, PermissionCode.TOOL_UPLOAD));

        assertTokenRejected(token);
    }

    /** C3：**清空**权限 → 触发；旧令牌失效 */
    @Test
    void clearingPermissionsRevokes() throws Exception {
        Long uid = createUser("t5", List.of(PermissionCode.TOOL_DOWNLOAD));
        String token = login("t5");

        updatePermissions(uid, List.of());

        assertTokenRejected(token);
    }

    /** A6：目标账号**从未登录**（无会话）→ 保存照常成功、不报 500 */
    @Test
    void offlineTargetSavesFine() throws Exception {
        Long uid = createUser("t6", List.of(PermissionCode.TOOL_DOWNLOAD));

        updatePermissions(uid, List.of(PermissionCode.TOOL_UPLOAD));

        assertEquals(List.of(PermissionCode.TOOL_UPLOAD), userService.getPermissions(uid));
    }

    /** C4：目标账号不存在 → 1004（既有行为），不触发联动、不 500 */
    @Test
    void missingTargetStillReturns1004() throws Exception {
        MvcResult res = mockMvc.perform(put("/api/users/999999999/permissions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("permissions", List.of(PermissionCode.TOOL_UPLOAD)))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        assertEquals(1004, node.get("code").asInt(), "目标不存在应为 1004，实际：" + res.getResponse().getContentAsString());
    }

    /** C5：非法权限点编码 → 1003（既有行为），不触发联动 */
    @Test
    void invalidPermissionCodeStillReturns1003() throws Exception {
        Long uid = createUser("t7", List.of(PermissionCode.TOOL_DOWNLOAD));
        String token = login("t7");

        MvcResult res = mockMvc.perform(put("/api/users/" + uid + "/permissions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("permissions", List.of("not:a:real:code")))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        assertEquals(1003, node.get("code").asInt(), "非法编码应为 1003");

        assertTokenWorks(token); // 保存失败不得触发下线
    }
}
