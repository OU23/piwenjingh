package com.example.toolbox.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.PermissionDict;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.service.ToolGrantService;
import com.example.toolbox.user.dto.UserSaveDTO;
import com.example.toolbox.user.entity.UserPermissionDO;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理员账号保护（M-22 / 契约 v1.13）。
 *
 * <p>覆盖验收 <b>A1–A10</b> 与 <b>C1–C8</b>：
 * <ul>
 *   <li>管理员权限点不可被修改（含自己）、不可被停用、不可被配工具授权、不出现在用户列表；</li>
 *   <li>普通账号行为完全不变（回归）；</li>
 *   <li><b>自愈通道</b>：已不含 `sys:setting` 的账号可被正常赋权（不得因"曾经是管理员"而拒绝）。</li>
 * </ul>
 */
@AutoConfigureMockMvc
class AdminAccountProtectionTest extends IntegrationTestBase {

    private static final String PWD = "pass123456";
    /** 仅用于夹具上下文（建账号时审计取当前操作人） */
    private static final long FIXTURE_ID = 999_961L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserService userService;
    @Autowired
    private ToolGrantService toolGrantService;
    @Autowired
    private ObjectMapper objectMapper;

    private Long deptId;
    /** 全局管理员（持 sys:setting） */
    private Long adminId;
    /** 普通管理者（持 user:manage + user:grant），用于发起"越权"尝试 */
    private Long operatorId;
    private String operatorToken;
    private String adminToken;

    @BeforeEach
    void setUpFixture() throws Exception {
        DeptDO d = new DeptDO();
        d.setName("管理员保护部");
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);
        deptId = d.getId();

        // 普通管理者（有 user:manage + user:grant —— 即联调中"能停用管理员"的那类账号）
        operatorId = createUser("op", List.of(PermissionCode.USER_MANAGE, PermissionCode.USER_GRANT));
        operatorToken = login("op");

        // 真实管理员账号（A9 隐性覆盖：新建时赋予 sys:setting 必须成功）
        adminId = createUser("boss",
                List.of(PermissionCode.SYS_SETTING, PermissionCode.TOOL_DOWNLOAD));
        adminToken = login("boss");
    }

    // ---------------- helpers ----------------

    private Long createUser(String username, List<String> perms) {
        loginAs(FIXTURE_ID, "fixture", deptId,
                PermissionCode.USER_MANAGE, PermissionCode.USER_GRANT);
        UserSaveDTO dto = new UserSaveDTO();
        dto.setUsername(username);
        dto.setRealName("测试账号");
        dto.setPassword(PWD);
        dto.setDeptId(String.valueOf(deptId));
        dto.setPermissions(perms);
        return Long.valueOf(userService.create(dto));
    }

    private String login(String username) throws Exception {
        return login(username, false);
    }

    /** force = true：允许替换已有会话（同账号重复登录时避免 1008 —— 那是 B8 的正常行为） */
    private String login(String username, boolean force) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PWD
                                + "\",\"force\":" + force + "}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        assertEquals(0, node.get("code").asInt(), "登录应成功：" + res.getResponse().getContentAsString());
        return node.get("data").get("token").asText();
    }

    private MvcResult putPermissions(Long targetId, List<String> perms, String token) throws Exception {
        return mockMvc.perform(put("/api/users/" + targetId + "/permissions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("permissions", perms))))
                .andReturn();
    }

    private MvcResult putStatus(Long targetId, int statusValue, String token) throws Exception {
        return mockMvc.perform(put("/api/users/" + targetId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":" + statusValue + "}"))
                .andReturn();
    }

    private int codeOf(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("code").asInt();
    }

    private Long createTool() {
        ToolDO t = new ToolDO();
        t.setName("保护测试工具");
        t.setIntro("x");
        t.setFileExt("zip");
        t.setScope("PUBLIC");
        t.setDeptId(deptId);
        t.setUploaderId(operatorId);
        t.setLatestVersionId(0L);
        t.setLatestVersion("v1.0");
        t.setFileSize(1L);
        t.setDownloadCount(0);
        t.setDeleted(0);
        t.setCreateTime(LocalDateTime.now());
        toolMapper.insert(t);
        return t.getId();
    }

    // ---------------- A1 / A2 / C4：权限点保护 ----------------

    /** A1：改管理员的权限点（他人操作）→ 1005，且权限点未变 */
    @Test
    void changingAdminPermissionsRejected() throws Exception {
        List<String> before = userService.getPermissions(adminId);

        MvcResult r = putPermissions(adminId, List.of(PermissionCode.TOOL_DOWNLOAD), operatorToken);

        assertEquals(1005, codeOf(r), "改管理员权限应 1005：" + r.getResponse().getContentAsString());
        assertEquals(before, userService.getPermissions(adminId), "管理员权限点不得发生任何变化");
    }

    /** A2：管理员改**自己** → 1005，且 sys:setting 仍在（豁免不失效） */
    @Test
    void adminSelfChangeRejected() throws Exception {
        MvcResult r = putPermissions(adminId, List.of(), adminToken);

        assertEquals(1005, codeOf(r), "管理员改自己也应被拒");
        assertTrue(userService.getPermissions(adminId).contains(PermissionCode.SYS_SETTING),
                "sys:setting 必须仍在");
        assertTrue(userService.isGlobalAdmin(adminId), "仍应被识别为管理员");
    }

    /** C4：改自己被拒后**会话不受影响**（拒绝发生在校验阶段，不得触发 M-21 的会话联动） */
    @Test
    void rejectedSelfChangeDoesNotKillSession() throws Exception {
        putPermissions(adminId, List.of(), adminToken);

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // ---------------- A3 / A4 / C2：停用保护 ----------------

    /** A3：停用管理员 → 1005，且管理员仍可正常登录（未被停用、未被踢） */
    @Test
    void disablingAdminRejected() throws Exception {
        MvcResult r = putStatus(adminId, 0, operatorToken);

        assertEquals(1005, codeOf(r), "停用管理员应 1005：" + r.getResponse().getContentAsString());
        // 旧令牌仍可用（未被踢）
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        // 且仍能重新登录（用 force 避开"已有活跃会话 → 1008"，那是 B8 的正常行为）
        assertNotNull(login("boss", true));
    }

    /** A4 / C2：**启用**管理员不受限 */
    @Test
    void enablingAdminAllowed() throws Exception {
        MvcResult r = putStatus(adminId, 1, operatorToken);

        assertEquals(0, codeOf(r), "启用管理员应成功：" + r.getResponse().getContentAsString());
    }

    // ---------------- A5 / A6 / C3：授权保护 ----------------

    /** A5：给管理员配工具授权（用户侧）→ 1005 */
    @Test
    void grantToolsToAdminRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolGrantService.saveToolsForUser(adminId, List.of(), operatorId));
        assertEquals(Code.CONFLICT, ex.getCode(), "应复用 1005");
    }

    /** A6 / C3：工具侧入参含管理员 → **整体拒绝**且**无任何写入** */
    @Test
    void grantAdminInUserListRejected() {
        Long toolId = createTool();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolGrantService.saveUsersForTool(toolId,
                        List.of(String.valueOf(adminId)), operatorId));
        assertEquals(Code.CONFLICT, ex.getCode());

        assertTrue(toolGrantMapper.selectUserIdsByTool(toolId).isEmpty(), "不得产生任何授权记录");
    }

    // ---------------- A7 / C5：列表排除 ----------------

    /** A7：用户列表不含管理员 */
    @Test
    void userListExcludesAdmin() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andReturn();
        String body = r.getResponse().getContentAsString();

        assertFalse(body.contains("boss"), "用户列表不得出现管理员账号：" + body);
        assertTrue(body.contains("op"), "普通账号应正常返回");
    }

    /** C5：按管理员账号名做关键词搜索 → 空列表（不得因命中而泄漏） */
    @Test
    void searchingAdminReturnsEmpty() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/users").param("keyword", "boss")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode list = objectMapper.readTree(r.getResponse().getContentAsString())
                .get("data").get("list");
        assertEquals(0, list.size(), "按管理员账号名搜索应为空");
    }

    // ---------------- A8 / A10 / C1 / C6：回归与自愈 ----------------

    /** A8 + C6：**普通账号**的改权限 / 停用行为完全不变（回归） */
    @Test
    void ordinaryUserUnaffected() throws Exception {
        Long u = createUser("normal", List.of(PermissionCode.TOOL_DOWNLOAD));

        assertEquals(0, codeOf(putPermissions(u, List.of(PermissionCode.TOOL_UPLOAD), operatorToken)),
                "普通账号改权限应成功");
        assertEquals(0, codeOf(putStatus(u, 0, operatorToken)), "普通账号停用应成功");
    }

    /** A10 + C1：**自愈通道** —— 已不含 sys:setting 的账号可被正常赋权 */
    @Test
    void selfHealingChannelWorks() throws Exception {
        // 模拟"曾被误改"：直接移除 sys:setting（绕过 API）
        userPermissionMapper.delete(Wrappers.<UserPermissionDO>lambdaQuery()
                .eq(UserPermissionDO::getUserId, adminId)
                .eq(UserPermissionDO::getPermissionCode, PermissionCode.SYS_SETTING));
        assertFalse(userService.isGlobalAdmin(adminId), "前置：该账号已不再是管理员");

        // 此时必须**可被正常恢复**（不得因"曾经是管理员"而拒绝）
        MvcResult r = putPermissions(adminId,
                List.of(PermissionCode.SYS_SETTING, PermissionCode.TOOL_DOWNLOAD), operatorToken);

        assertEquals(0, codeOf(r),
                "自愈通道：权限恢复不得被拒 —— " + r.getResponse().getContentAsString());
        assertTrue(userService.isGlobalAdmin(adminId), "恢复后应重新成为管理员");
    }

    // ---------------- A11 / C7（本次修正核心）----------------

    /**
     * A11 / C7（M-22 v1.13 修正核心）：被赋予全量权限（含 sys:setting）的**普通账号**
     * —— 仍正常**可见**、可被**停用 / 改权限 / 配授权**（与"系统管理员"身份解耦）。
     * 这正是 bug 现场：test1 被赋全部权限后在用户管理里消失；修正后 test1 类账号照常可见可管理。
     */
    @Test
    void fullPermsOrdinaryAccountIsManageableAndVisible() throws Exception {
        // deputy 在 boss（首个 sys:setting 持有者 = 系统管理员）之后创建 → id 更大 → 不是系统管理员
        Long deputyId = createUser("deputy", PermissionDict.allCodes());

        // 列表应**包含** deputy（不得因持有 sys:setting 而被隐藏）
        MvcResult r = mockMvc.perform(get("/api/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                .andExpect(status().isOk()).andReturn();
        assertTrue(r.getResponse().getContentAsString().contains("deputy"),
                "全权普通账号应可见（不得被误当管理员隐藏）：" + r.getResponse().getContentAsString());

        // 可被停用（普通账号行为，不受"管理员保护"约束）
        assertEquals(0, codeOf(putStatus(deputyId, 0, operatorToken)), "全权普通账号应可被停用");
        assertEquals(0, codeOf(putStatus(deputyId, 1, operatorToken)), "全权普通账号应可被启用");
        // 可被改权限（普通账号行为）
        assertEquals(0, codeOf(putPermissions(deputyId,
                List.of(PermissionCode.TOOL_DOWNLOAD), operatorToken)), "全权普通账号应可被改权限");
        // 可被配工具授权（不得 1005）
        toolGrantService.saveToolsForUser(deputyId, List.of(), operatorId);
    }
}
