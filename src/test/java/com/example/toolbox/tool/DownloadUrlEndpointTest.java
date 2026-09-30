package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.storage.OssStorageService;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.entity.UserPermissionDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 下载地址签发端点（M-22 / B13 验收 A8 · A14 · A15）。
 *
 * <p>走**完整过滤链**验证真实 HTTP 语义：
 * <ul>
 *   <li>未登录 → 真 `401`；无权限 / 不可见 → 真 `403`；资源已不存在 → 真 `404`（与 §9.5 同口径）；</li>
 *   <li>分流：本地 → `direct=false` 的后端令牌地址；OSS → `direct=true` 的签名直链；</li>
 *   <li>★ <b>E-02 隔离</b>：OSS 故障时本接口 `HTTP 200 + 2006`（**不是 500**），且
 *       **登录（`/api/auth/me`）、检索（`/api/tools`）、本地文件下载全部照常**。</li>
 * </ul>
 */
@AutoConfigureMockMvc
class DownloadUrlEndpointTest extends IntegrationTestBase {

    private static final String SIGNED_URL =
            "https://toolbox-files.oss-cn-hangzhou.aliyuncs.com/tools/abc.zip?Expires=1759212600&Signature=x";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StorageService storageService;

    @MockBean
    private OssStorageService ossStorageService;

    private Long deptId;
    private Long otherDeptId;
    private Long localToolId;
    private Long ossToolId;
    private Long missingFileToolId;
    private UserDO user;
    private UserDO otherDeptUser;
    private UserDO noPermUser;

    @BeforeEach
    void setUpData() {
        DeptDO d1 = new DeptDO();
        d1.setName("研发部");
        d1.setStatus(StatusValue.ENABLED);
        d1.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d1);
        deptId = d1.getId();

        DeptDO d2 = new DeptDO();
        d2.setName("市场部");
        d2.setStatus(StatusValue.ENABLED);
        d2.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d2);
        otherDeptId = d2.getId();

        user = insertUser("zhangsan", deptId, PermissionCode.TOOL_DOWNLOAD);
        otherDeptUser = insertUser("lisi", otherDeptId, PermissionCode.TOOL_DOWNLOAD);
        noPermUser = insertUser("wangwu", deptId);

        localToolId = insertTool("本地工具", true, "local");
        ossToolId = insertTool("OSS 工具", true, "oss");
        missingFileToolId = insertTool("文件已丢", false, "local");
    }

    // ---------------- A8 鉴权先于签发（真实状态码） ----------------

    @Test
    void unauthenticatedGetsReal401() throws Exception {
        mockMvc.perform(get("/api/tools/" + localToolId + "/download-url"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void withoutPermissionGetsReal403() throws Exception {
        mockMvc.perform(get("/api/tools/" + localToolId + "/download-url")
                        .header("Authorization", "Bearer " + tokenFor(noPermUser)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(Code.FORBIDDEN.getCode()));
    }

    @Test
    void otherDeptUserGetsReal403() throws Exception {
        mockMvc.perform(get("/api/tools/" + localToolId + "/download-url")
                        .header("Authorization", "Bearer " + tokenFor(otherDeptUser, PermissionCode.TOOL_DOWNLOAD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletedToolGets404With2004() throws Exception {
        ToolDO tool = toolMapper.selectById(localToolId);
        tool.setDeleted(1);
        toolMapper.updateById(tool);

        mockMvc.perform(get("/api/tools/" + localToolId + "/download-url")
                        .header("Authorization", "Bearer " + tokenFor(user, PermissionCode.TOOL_DOWNLOAD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(Code.TOOL_DELETED.getCode()));
    }

    @Test
    void missingPhysicalFileGets404With2005() throws Exception {
        mockMvc.perform(get("/api/tools/" + missingFileToolId + "/download-url")
                        .header("Authorization", "Bearer " + tokenFor(user, PermissionCode.TOOL_DOWNLOAD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(Code.FILE_KEY_INVALID.getCode()));
    }

    // ---------------- A5 / A6 分流 ----------------

    @Test
    void localFileReturnsProxiedUrl() throws Exception {
        mockMvc.perform(get("/api/tools/" + localToolId + "/download-url")
                        .header("Authorization", "Bearer " + tokenFor(user, PermissionCode.TOOL_DOWNLOAD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.direct").value(false))
                .andExpect(jsonPath("$.data.url", startsWith("/api/tools/")))
                .andExpect(jsonPath("$.data.expiresIn").isNumber());
    }

    @Test
    void ossFileReturnsSignedDirectUrl() throws Exception {
        when(ossStorageService.exists(anyString())).thenReturn(true);
        when(ossStorageService.presignedUrl(anyString(), anyString(), any())).thenReturn(Optional.of(SIGNED_URL));

        mockMvc.perform(get("/api/tools/" + ossToolId + "/download-url")
                        .header("Authorization", "Bearer " + tokenFor(user, PermissionCode.TOOL_DOWNLOAD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.direct").value(true))
                .andExpect(jsonPath("$.data.url").value(SIGNED_URL));
    }

    // ---------------- A14 / E-02 隔离（本阶段最重要的反向用例） ----------------

    @Test
    void ossOutageYields200With2006AndLeavesOtherFeaturesIntact() throws Exception {
        String token = tokenFor(user, PermissionCode.TOOL_DOWNLOAD);
        when(ossStorageService.exists(anyString()))
                .thenThrow(new com.example.toolbox.common.exception.BusinessException(
                        Code.STORAGE_OSS_UNAVAILABLE, "对象存储暂不可用"));

        // ① OSS 文件取地址：HTTP 200 + 2006（不是 500、也不是 404/2005）
        mockMvc.perform(get("/api/tools/" + ossToolId + "/download-url").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(Code.STORAGE_OSS_UNAVAILABLE.getCode()));

        // ② 登录态接口照常
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // ③ 检索（工具列表）照常
        mockMvc.perform(get("/api/tools").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // ④ 本地文件下载照常（不因 OSS 故障而连带失败）
        mockMvc.perform(get("/api/tools/" + localToolId + "/download").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // ⑤ 本地文件取地址照常（direct=false）
        mockMvc.perform(get("/api/tools/" + localToolId + "/download-url")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.direct").value(false));
    }

    // ---------------- 内部 ----------------

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

    private String tokenFor(UserDO u, String... perms) {
        return signToken(LoginUser.builder()
                .userId(u.getId()).username(u.getUsername()).realName(u.getRealName())
                .deptId(u.getDeptId()).permissions(Set.of(perms)).build());
    }

    /** 建一个 DEPT 范围工具（saveFile=false 时故意不落盘，用于"物理文件缺失"） */
    private Long insertTool(String name, boolean saveFile, String backend) {
        byte[] content = new byte[32];
        String key = storageService.generateKey();
        if (saveFile) {
            try {
                storageService.save(key, new ByteArrayInputStream(content), content.length);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        ToolDO tool = new ToolDO();
        tool.setName(name);
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

        ToolVersionDO v = new ToolVersionDO();
        v.setToolId(tool.getId());
        v.setVersion("v1.0");
        v.setFileKey(key);
        v.setFileName(name + ".bin");
        v.setFileExt("bin");
        v.setFileSize((long) content.length);
        v.setUploaderId(user.getId());
        v.setDownloadCount(0);
        v.setStorageBackend(backend);
        v.setCreateTime(now);
        toolVersionMapper.insert(v);

        tool.setLatestVersionId(v.getId());
        toolMapper.updateById(tool);
        return tool.getId();
    }
}
