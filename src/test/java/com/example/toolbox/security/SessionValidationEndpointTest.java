package com.example.toolbox.security;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.service.DownloadTokenService;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.entity.UserPermissionDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 每请求会话校验（M-18 / 契约 §3.2）—— 接口层。
 *
 * <p>走完整过滤链（JwtAuthFilter + DownloadController 令牌通道），
 * 覆盖验收 <b>A2 / A5 / A6 / A11 / C10</b>。
 * ★ A5（{@code ?token=} 下载路径）是本阶段最关键的"漏校验"风险点。
 */
@AutoConfigureMockMvc
class SessionValidationEndpointTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StorageService storageService;
    @Autowired
    private DownloadTokenService downloadTokenService;
    @Autowired
    private JwtProvider jwtProvider;

    private Long deptId;
    private Long toolId;
    private UserDO user;

    @BeforeEach
    void setUpData() {
        DeptDO d = new DeptDO();
        d.setName("研发部");
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);
        deptId = d.getId();

        user = insertUser("zhangsan", deptId, PermissionCode.TOOL_DOWNLOAD);

        byte[] content = new byte[32];
        String key = storageService.generateKey();
        try {
            storageService.save(key, new ByteArrayInputStream(content), content.length);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        LocalDateTime now = LocalDateTime.now();
        ToolDO tool = new ToolDO();
        tool.setName("会话校验工具");
        tool.setIntro("简介");
        tool.setFileExt("bin");
        tool.setScope(Scope.DEPT);
        tool.setDeptId(deptId);
        tool.setUploaderId(user.getId());
        tool.setLatestVersion("v1.0");
        tool.setFileSize((long) content.length);
        tool.setDownloadCount(0);
        tool.setDeleted(0);
        tool.setCreateTime(now);
        tool.setUpdateTime(now);
        tool.setLatestVersionId(0L);
        toolMapper.insert(tool);
        toolId = tool.getId();

        ToolVersionDO v = new ToolVersionDO();
        v.setToolId(toolId);
        v.setVersion("v1.0");
        v.setFileKey(key);
        v.setFileName("a.bin");
        v.setFileExt("bin");
        v.setFileSize((long) content.length);
        v.setUploaderId(user.getId());
        v.setDownloadCount(0);
        v.setCreateTime(now);
        toolVersionMapper.insert(v);

        tool.setLatestVersionId(v.getId());
        toolMapper.updateById(tool);

        // 登录上下文 + 当前会话（sid = test-sid-<uid>）
        loginAs(user.getId(), "zhangsan", deptId, PermissionCode.TOOL_DOWNLOAD);
    }

    private UserDO insertUser(String username, Long dept, String... perms) {
        UserDO u = new UserDO();
        u.setUsername(username);
        u.setPassword("$2a$10$placeholder-shape-valid");
        u.setRealName("测试用户");
        u.setDeptId(dept);
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
        return u;
    }

    /** 造一个"当前会话有效"的 Bearer 令牌 */
    private String validToken() {
        return signToken(LoginUser.builder()
                .userId(user.getId()).username("zhangsan").deptId(deptId)
                .permissions(Set.of(PermissionCode.TOOL_DOWNLOAD)).build());
    }

    /** A2：会话被替代后，旧令牌请求任一需鉴权接口 → 真 401 + 1001（不是 1008） */
    @Test
    void staleTokenGetsReal401WithCode1001() throws Exception {
        String old = validToken();
        sessionRegistry.establish(user.getId(), "new-session");

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + old))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    /**
     * A5 ★：{@code ?token=} 短时效令牌在**换发它的会话失效后** → 401（不得放行）。
     * 用例内先证明"会话有效时该令牌可下载"，再证明"会话失效后同一机制拒绝"，
     * 避免写出"无论如何都 401"的假测试。
     */
    @Test
    void downloadTokenRejectedAfterSessionReplaced() throws Exception {
        // ① 会话有效：令牌可用（正向基线）
        String okToken = downloadTokenService.issue(toolId, null).getToken();
        mockMvc.perform(get("/api/tools/" + toolId + "/download").param("token", okToken))
                .andExpect(status().isOk());

        // ② 另一会话换发的新令牌 → 会话被替代 → 401
        String token = downloadTokenService.issue(toolId, null).getToken();
        sessionRegistry.establish(user.getId(), "new-session");

        mockMvc.perform(get("/api/tools/" + toolId + "/download").param("token", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    /** A6：HTTP（Bearer）通道同样校验会话 —— 旧令牌下载 → 401 */
    @Test
    void bearerDownloadRejectedAfterSessionReplaced() throws Exception {
        String old = validToken();
        sessionRegistry.establish(user.getId(), "new-session");

        mockMvc.perform(get("/api/tools/" + toolId + "/download")
                        .header("Authorization", "Bearer " + old))
                .andExpect(status().isUnauthorized());
    }

    /** C10：会话被替代后的判定稳定 —— 连续请求一律 401，不出现"时而通过" */
    @Test
    void staleJudgementIsStableAcrossRequests() throws Exception {
        String old = validToken();
        sessionRegistry.establish(user.getId(), "new-session");

        for (int i = 0; i < 8; i++) {
            mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + old))
                    .andExpect(status().isUnauthorized());
        }
    }

    /** A11：生产配置令牌有效期 12 小时（expiresIn = 43200）。响应字段口径见 SingleLoginAuthServiceTest */
    @Test
    void productionTtlIsTwelveHours() throws Exception {
        String yml = new String(new ClassPathResource("application.yml")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(yml.contains("expiration-minutes: 720"),
                "生产配置应为 12 小时（720 分钟）");
        assertEquals(43_200L, 720L * 60L, "12 小时 = 43200 秒");
    }
}
