package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.JwtProvider;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.service.ToolService;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.entity.UserPermissionDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工具定向授权（M-17）—— 接口层（验收 A7 / A10 / C3 / C4）。
 *
 * <p>走完整过滤链（JwtAuthFilter + PermissionInterceptor + GlobalExceptionHandler）：
 * 真 401 / 真 403、路径映射、以及 A10「GET /api/users 放宽但建号 / 改号仍 403」。
 * 业务语义（双向一致、去重、清空等）见 {@code ToolGrantServiceTest}。
 */
@AutoConfigureMockMvc
class ToolGrantEndpointTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;

    private Long deptA;
    private Long deptB;
    private Long targetId;
    private Long toolId;

    @BeforeEach
    void setUpData() {
        DeptDO da = new DeptDO();
        da.setName("研发部");
        da.setStatus(StatusValue.ENABLED);
        da.setCreateTime(LocalDateTime.now());
        deptMapper.insert(da);
        deptA = da.getId();

        DeptDO db = new DeptDO();
        db.setName("市场部");
        db.setStatus(StatusValue.ENABLED);
        db.setCreateTime(LocalDateTime.now());
        deptMapper.insert(db);
        deptB = db.getId();

        targetId = insertUser("grantee", deptB).getId();

        ToolDO tool = new ToolDO();
        tool.setName("授权目标工具");
        tool.setIntro("简介");
        tool.setFileExt("zip");
        tool.setScope(Scope.DEPT);
        tool.setDeptId(deptA);
        tool.setUploaderId(1L);
        tool.setLatestVersion("v1.0");
        tool.setFileSize(8L);
        tool.setDownloadCount(0);
        tool.setDeleted(ToolService.NOT_DELETED);
        tool.setCreateTime(LocalDateTime.now());
        tool.setUpdateTime(LocalDateTime.now());
        tool.setLatestVersionId(0L);
        toolMapper.insert(tool);
        toolId = tool.getId();
    }

    // ---------------- A7：4 个授权接口均需 user:grant ----------------

    @Test
    void grantEndpointsRequireUserGrant() throws Exception {
        String userGrantsPath = "/api/users/" + targetId + "/tool-grants";
        String toolGrantsPath = "/api/tools/" + toolId + "/grants";

        // 未登录 → 真 401
        mockMvc.perform(get(userGrantsPath)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(userGrantsPath).contentType("application/json").content("{\"toolIds\":[]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(toolGrantsPath)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(toolGrantsPath).contentType("application/json").content("{\"userIds\":[]}"))
                .andExpect(status().isUnauthorized());

        // 登录但无 user:grant（持 tool:download）→ 真 403（1002）
        String noGrant = tokenWith(PermissionCode.TOOL_DOWNLOAD);
        mockMvc.perform(get(userGrantsPath).header("Authorization", "Bearer " + noGrant))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        mockMvc.perform(put(userGrantsPath).header("Authorization", "Bearer " + noGrant)
                        .contentType("application/json").content("{\"toolIds\":[\"" + toolId + "\"]}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        mockMvc.perform(get(toolGrantsPath).header("Authorization", "Bearer " + noGrant))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        mockMvc.perform(put(toolGrantsPath).header("Authorization", "Bearer " + noGrant)
                        .contentType("application/json").content("{\"userIds\":[\"" + targetId + "\"]}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
    }

    // ---------------- A2 / A3：用户侧授权流程（HTTP） ----------------

    @Test
    void userSideGrantFlowViaHttp() throws Exception {
        String token = tokenWith(PermissionCode.USER_GRANT);
        String path = "/api/users/" + targetId + "/tool-grants";

        // 保存
        mockMvc.perform(put(path).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"toolIds\":[\"" + toolId + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        // 查询：应返回该工具，字段齐备
        mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value(String.valueOf(toolId)))
                .andExpect(jsonPath("$.data[0].name").value("授权目标工具"))
                .andExpect(jsonPath("$.data[0].deptName").value("研发部"))
                .andExpect(jsonPath("$.data[0].latestVersion").value("v1.0"));

        // 清空
        mockMvc.perform(put(path).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"toolIds\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ---------------- A5：工具侧授权流程（HTTP） ----------------

    @Test
    void toolSideGrantFlowViaHttp() throws Exception {
        String token = tokenWith(PermissionCode.USER_GRANT);
        String path = "/api/tools/" + toolId + "/grants";

        mockMvc.perform(put(path).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"userIds\":[\"" + targetId + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(String.valueOf(targetId)))
                .andExpect(jsonPath("$.data[0].username").value("grantee"))
                .andExpect(jsonPath("$.data[0].realName").value("测试用户"))
                .andExpect(jsonPath("$.data[0].deptName").value("市场部"));
    }

    // ---------------- A4 / C4：无效 ID → 1004（HTTP） ----------------

    @Test
    void invalidToolIdReturns1004ViaHttp() throws Exception {
        String token = tokenWith(PermissionCode.USER_GRANT);
        mockMvc.perform(put("/api/users/" + targetId + "/tool-grants")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"toolIds\":[\"999999\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1004));
    }

    // ---------------- C3：缺失 / 非数组 → 1003 ----------------

    @Test
    void missingOrNonArrayToolIdsReturns1003ViaHttp() throws Exception {
        String token = tokenWith(PermissionCode.USER_GRANT);
        String path = "/api/users/" + targetId + "/tool-grants";

        // 缺失字段 → 入参 null → 1003
        mockMvc.perform(put(path).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{}"))
                .andExpect(jsonPath("$.code").value(1003));

        // 非数组（标量）→ HttpMessageNotReadable → 1003
        mockMvc.perform(put(path).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"toolIds\":\"not-an-array\"}"))
                .andExpect(jsonPath("$.code").value(1003));
    }

    // ---------------- A10：GET /api/users 放宽，但建号 / 改号仍 403 ----------------

    @Test
    void getUserListRelaxedButCreateStill403() throws Exception {
        String onlyGrant = tokenWith(PermissionCode.USER_GRANT);

        // 仅持 user:grant 可调通 GET /api/users
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + onlyGrant))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        // 建号（POST）仅 user:grant → 403（需 user:manage）
        mockMvc.perform(post("/api/users").header("Authorization", "Bearer " + onlyGrant)
                        .contentType("application/json").content("{\"username\":\"x\",\"realName\":\"x\",\"deptId\":1}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));

        // 改号（PUT）仅 user:grant → 403（需 user:manage）
        mockMvc.perform(put("/api/users/" + targetId).header("Authorization", "Bearer " + onlyGrant)
                        .contentType("application/json").content("{\"realName\":\"x\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
    }

    // ---------------- 夹具 ----------------

    private UserDO insertUser(String username, Long dept) {
        UserDO u = new UserDO();
        u.setUsername(username);
        u.setPassword("$2a$10$placeholder-shape-valid");
        u.setRealName("测试用户");
        u.setDeptId(dept);
        u.setStatus(StatusValue.ENABLED);
        u.setCreateTime(LocalDateTime.now());
        userMapper.insert(u);
        return u;
    }

    private String tokenWith(String... perms) {
        return signToken(LoginUser.builder()
                .userId(1L).username("grantor").deptId(deptA)
                .permissions(Set.of(perms)).build());
    }
}
