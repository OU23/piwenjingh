package com.example.toolbox.auth;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.SessionService;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登出与停用联动（M-18 / 契约 §3.2 规则 6 + §4.4）。
 * 覆盖验收 <b>A9 / A10 / C7 / C8</b>：会话终结后原令牌必须 401，且不得借 A1 复活。
 */
@AutoConfigureMockMvc
class SessionLifecycleTest extends IntegrationTestBase {

    private static final long ADMIN_ID = 999_999L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserService userService;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private ObjectMapper objectMapper;

    private Long deptId;
    private Long userId;
    private String adminToken;

    @BeforeEach
    void setUpData() {
        DeptDO d = new DeptDO();
        d.setName("研发部");
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);
        deptId = d.getId();

        // 管理员上下文 + 管理员令牌（用于调用停用接口）
        loginAs(ADMIN_ID, "admin", deptId, PermissionCode.USER_MANAGE);
        adminToken = signToken(LoginUser.builder()
                .userId(ADMIN_ID).username("admin").deptId(deptId)
                .permissions(Set.of(PermissionCode.USER_MANAGE)).build());

        UserSaveDTO dto = new UserSaveDTO();
        dto.setUsername("u1");
        dto.setRealName("测试用户");
        dto.setPassword("pass123456");
        dto.setDeptId(String.valueOf(deptId));
        dto.setPermissions(List.of(PermissionCode.TOOL_DOWNLOAD));
        userId = Long.valueOf(userService.create(dto));
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        assertEquals(0, node.get("code").asInt(), "登录应成功：" + body);
        return node.get("data").get("token").asText();
    }

    private void changeStatus(int status, String token) throws Exception {
        mockMvc.perform(put("/api/users/" + userId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":" + status + "}"))
                .andExpect(status().isOk());
    }

    /** A9：logout → 会话移除，原令牌 401 */
    @Test
    void logoutRemovesSessionAndInvalidatesToken() throws Exception {
        String token = loginAndGetToken("u1", "pass123456");

        mockMvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /** A10：账号被停用 → 立即踢下线，原令牌 401 */
    @Test
    void disablingAccountKicksSessionImmediately() throws Exception {
        String token = loginAndGetToken("u1", "pass123456");

        changeStatus(0, adminToken);

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /** C7：logout 不存在的会话 → 幂等，不抛异常（不报 500） */
    @Test
    void logoutOfNonexistentSessionIsIdempotent() {
        assertDoesNotThrow(() -> sessionService.logout(123_456_789L), "不存在的会话应幂等");
        assertDoesNotThrow(() -> sessionService.logout(null), "null 应安全");
        assertDoesNotThrow(() -> sessionService.forceLogout(123_456_789L), "强制下线不存在会话应幂等");
    }

    /** C8：停用后又启用 → 旧令牌仍为 401，须重新登录才有有效会话 */
    @Test
    void reenabledAccountRequiresFreshLogin() throws Exception {
        String stale = loginAndGetToken("u1", "pass123456");

        changeStatus(0, adminToken);
        changeStatus(1, adminToken);

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + stale))
                .andExpect(status().isUnauthorized());

        String fresh = loginAndGetToken("u1", "pass123456");
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + fresh))
                .andExpect(status().isOk());
    }
}
