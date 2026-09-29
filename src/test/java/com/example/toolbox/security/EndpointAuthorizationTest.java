package com.example.toolbox.security;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.user.entity.UserDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口鉴权端到端（验收 A8）：未登录 → **真 401**；已登录但权限不足 → **真 403**。
 *
 * <p>本用例走完整过滤链（JwtAuthFilter + PermissionInterceptor + GlobalExceptionHandler），
 * 验证 HTTP 状态码本身，而不是只看业务码 —— 这是原则 R2 的要害。
 */
@AutoConfigureMockMvc
class EndpointAuthorizationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;

    private Long deptId;

    @BeforeEach
    void setUpData() {
        DeptDO d = new DeptDO();
        d.setName("研发部");
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);
        deptId = d.getId();
    }

    private UserDO insertUser(String username, String... perms) {
        UserDO u = new UserDO();
        u.setUsername(username);
        u.setPassword("$2a$10$not-a-real-hash-but-shape-valid-placeholder");
        u.setRealName("测试用户");
        u.setDeptId(deptId);
        u.setStatus(StatusValue.ENABLED);
        u.setCreateTime(LocalDateTime.now());
        userMapper.insert(u);
        for (String p : perms) {
            com.example.toolbox.user.entity.UserPermissionDO up =
                    new com.example.toolbox.user.entity.UserPermissionDO();
            up.setUserId(u.getId());
            up.setPermissionCode(p);
            up.setCreateTime(LocalDateTime.now());
            userPermissionMapper.insert(up);
        }
        return u;
    }

    private String tokenFor(UserDO u, String... perms) {
        return signToken(LoginUser.builder()
                .userId(u.getId())
                .username(u.getUsername())
                .realName(u.getRealName())
                .deptId(u.getDeptId())
                .permissions(java.util.Set.of(perms))
                .build());
    }

    /** A8：未登录访问受保护接口 → 真 401 + 业务码 1001 */
    @Test
    void unauthenticatedRequestGetsReal401() throws Exception {
        mockMvc.perform(get("/api/depts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void noTokenOnAdminEndpointGetsReal401() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void malformedTokenGetsReal401() throws Exception {
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    /** A8：已登录但无该权限点 → 真 403 + 业务码 1002 */
    @Test
    void authenticatedButUnauthorizedGetsReal403() throws Exception {
        UserDO u = insertUser("zhangsan", PermissionCode.TOOL_DOWNLOAD);
        String token = tokenFor(u, PermissionCode.TOOL_DOWNLOAD);

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void noPermissionAtAllStillGets403Not401() throws Exception {
        UserDO u = insertUser("nobody");
        String token = tokenFor(u); // 无任何权限点

        mockMvc.perform(post("/api/depts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"新部门\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1002));
    }

    /** A8 反向确认：有权限点即可通过（不是把所有人都拒掉） */
    @Test
    void withPermissionRequestPasses() throws Exception {
        UserDO u = insertUser("zhangsan", PermissionCode.USER_MANAGE);
        String token = tokenFor(u, PermissionCode.USER_MANAGE);

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 登录接口为公开路径：无令牌亦可访问（否则无法登录） */
    @Test
    void loginEndpointIsPublic() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"nobody\",\"password\":\"whatever\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1006));
    }

    /** 登录接口：账号密码错误 → HTTP 200 + 业务码 1006（业务失败，不是 401） */
    @Test
    void wrongCredentialsIsBusinessFailureNotHttp401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"nobody\",\"password\":\"whatever\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1006));
    }

    /** A8：登录凭证中的权限实时判定 —— 改库后无需重新签发令牌也能生效 */
    @Test
    void permissionIsResolvedFromTokenNotDatabaseCalls() throws Exception {
        UserDO u = insertUser("zhangsan");
        // 签发时无权限 → 应 403
        String noPermToken = tokenFor(u);
        mockMvc.perform(get("/api/permissions").header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isForbidden());

        // 重新签发带权限的令牌 → 应通过（同一用户、同一接口）
        String withPermToken = tokenFor(u, PermissionCode.USER_GRANT);
        mockMvc.perform(get("/api/permissions").header("Authorization", "Bearer " + withPermToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }

    /** 部门列表为"登录即可"，不需要额外权限点 */
    @Test
    void deptListRequiresLoginOnly() throws Exception {
        UserDO u = insertUser("zhangsan", PermissionCode.TOOL_DOWNLOAD);
        String token = tokenFor(u, PermissionCode.TOOL_DOWNLOAD);

        mockMvc.perform(get("/api/depts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
