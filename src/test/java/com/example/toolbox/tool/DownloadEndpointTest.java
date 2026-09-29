package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.JwtProvider;
import com.example.toolbox.security.LoginUser;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 下载接口端到端（B3-T9）：走完整过滤链验证**真实 HTTP 语义**。
 *
 * <p>重点验证契约 §9.5 的响应头与状态码：
 * 200 / 206、Content-Range、Accept-Ranges、Content-Length、
 * Content-Disposition 中文双写，以及 401 / 403 的真实状态码。
 */
@AutoConfigureMockMvc
class DownloadEndpointTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private StorageService storageService;

    private Long deptId;
    private Long toolId;
    private Long versionId;
    private UserDO user;
    private UserDO otherDeptUser;
    private Long otherDeptId;

    private byte[] content;

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

        content = new byte[64];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        String key = storageService.generateKey();
        try {
            storageService.save(key, new ByteArrayInputStream(content), content.length);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        LocalDateTime now = LocalDateTime.now();
        ToolDO tool = new ToolDO();
        tool.setName("端到端工具");
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
        v.setFileName("中文名称.bin");
        v.setFileExt("bin");
        v.setFileSize((long) content.length);
        v.setUploaderId(user.getId());
        v.setDownloadCount(0);
        v.setCreateTime(now);
        toolVersionMapper.insert(v);
        versionId = v.getId();

        tool.setLatestVersionId(versionId);
        toolMapper.updateById(tool);
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

    private String tokenFor(UserDO u, String... perms) {
        return signToken(LoginUser.builder()
                .userId(u.getId()).username(u.getUsername()).realName(u.getRealName())
                .deptId(u.getDeptId()).permissions(Set.of(perms)).build());
    }

    /** 未登录下载 → 真 401 + 业务码 1001 */
    @Test
    void unauthenticatedGetsReal401() throws Exception {
        mockMvc.perform(get("/api/tools/" + toolId + "/download"))
                .andExpect(status().isUnauthorized());
    }

    /** 无 tool:download 权限 → 真 403 */
    @Test
    void withoutPermissionGetsReal403() throws Exception {
        String token = tokenFor(user);   // 不带任何权限点
        mockMvc.perform(get("/api/tools/" + toolId + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /** 跨部门 DEPT 工具 → 真 403（数据隔离） */
    @Test
    void otherDeptGetsReal403() throws Exception {
        String token = tokenFor(otherDeptUser, PermissionCode.TOOL_DOWNLOAD);
        mockMvc.perform(get("/api/tools/" + toolId + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /** 完整下载：200 + Content-Type octet-stream + Accept-Ranges + 中文文件名双写 */
    @Test
    void fullDownloadReturns200WithHeaders() throws Exception {
        String token = tokenFor(user, PermissionCode.TOOL_DOWNLOAD);

        MvcResult result = mockMvc.perform(get("/api/tools/" + toolId + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        String disposition = result.getResponse().getHeader("Content-Disposition");
        assertTrue(disposition.contains("attachment"), "应为附件下载：" + disposition);
        assertTrue(disposition.contains("filename*=UTF-8''"),
                "中文文件名需 UTF-8 编码名（契约 §9.5）：" + disposition);

        byte[] body = result.getResponse().getContentAsByteArray();
        assertTrue(body.length == content.length, "响应体应为完整文件内容");
    }

    /** Range 续传：206 + Content-Range + 只返回请求区间 */
    @Test
    void rangeDownloadReturns206() throws Exception {
        String token = tokenFor(user, PermissionCode.TOOL_DOWNLOAD);

        MvcResult result = mockMvc.perform(get("/api/tools/" + toolId + "/download")
                        .header("Authorization", "Bearer " + token)
                        .header("Range", "bytes=32-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().exists("Content-Range"))
                .andReturn();

        String contentRange = result.getResponse().getHeader("Content-Range");
        assertTrue(contentRange.startsWith("bytes 32-63/64"),
                "Content-Range 应正确反映区间与总长，实际：" + contentRange);

        byte[] body = result.getResponse().getContentAsByteArray();
        assertTrue(body.length == 32, "续传应只返回剩余 32 字节，实际：" + body.length);
    }

    /**
     * ★ **有界 Range**（契约 §9.5 例子的形式 {@code bytes=0-1048575}）：
     * 206 + `Content-Range` 必须等于**请求区间**，`Content-Length` 与响应体长度都必须等于区间长度。
     *
     * <p>该形态此前会被退化为 200 + 整包（只支持开放式 `bytes=N-`），是契约偏差；
     * 本用例锁死修正后的 HTTP 语义（含 Content-Length，防止"声称 8 字节却回整包"）。
     */
    @Test
    void boundedRangeDownloadReturns206WithExactInterval() throws Exception {
        String token = tokenFor(user, PermissionCode.TOOL_DOWNLOAD);

        MvcResult result = mockMvc.perform(get("/api/tools/" + toolId + "/download")
                        .header("Authorization", "Bearer " + token)
                        .header("Range", "bytes=8-15"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Range", "bytes 8-15/64"))
                .andExpect(header().string("Content-Length", "8"))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertTrue(body.length == 8, "有界区间应只返回 8 字节，实际：" + body.length);
        assertTrue(body[0] == content[8], "首字节应为原文件第 8 字节");
        assertTrue(body[7] == content[15], "末字节应为原文件第 15 字节");
    }

    /** HEAD：返回总大小 + Accept-Ranges，无响应体 */
    @Test
    void headReturnsSizeWithoutBody() throws Exception {
        String token = tokenFor(user, PermissionCode.TOOL_DOWNLOAD);

        MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .head("/api/tools/" + toolId + "/download")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andReturn();

        String length = result.getResponse().getHeader("Content-Length");
        assertTrue(content.length == Integer.parseInt(length),
                "HEAD 应返回文件总大小，实际：" + length);
        assertTrue(result.getResponse().getContentAsByteArray().length == 0, "HEAD 不应返回响应体");
    }

    /** 已删除工具下载 → 404 + 业务码 2004（B5 T2：资源已不存在） */
    @Test
    void deletedToolReturns404With2004() throws Exception {
        ToolDO tool = toolMapper.selectById(toolId);
        tool.setDeleted(1);
        toolMapper.updateById(tool);

        String token = tokenFor(user, PermissionCode.TOOL_DOWNLOAD);
        mockMvc.perform(get("/api/tools/" + toolId + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value(2004));
    }

    /** 不存在的工具下载 → 404 + 业务码 2004（B5 T2：下载链路收敛 1004→2004） */
    @Test
    void missingToolReturns404With2004() throws Exception {
        String token = tokenFor(user, PermissionCode.TOOL_DOWNLOAD);
        mockMvc.perform(get("/api/tools/999999/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value(2004));
    }

    /** 下载令牌接口：403 权限不足 / 200 正常签发且 expiresIn ≤ 60 */
    @Test
    void downloadTokenEndpoint() throws Exception {
        // 无权限 → 403
        String noPerm = tokenFor(user);
        mockMvc.perform(post("/api/tools/" + toolId + "/download-token")
                        .header("Authorization", "Bearer " + noPerm))
                .andExpect(status().isForbidden());

        // 有权限 → 200 + token
        String token = tokenFor(user, PermissionCode.TOOL_DOWNLOAD);
        mockMvc.perform(post("/api/tools/" + toolId + "/download-token")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.token").isNotEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.expiresIn").value(
                                org.hamcrest.Matchers.lessThanOrEqualTo(60)));
    }

    /** ?token= 通道：凭令牌可下载（原生 <a> 场景） */
    @Test
    void downloadWithTokenParam() throws Exception {
        String jwt = tokenFor(user, PermissionCode.TOOL_DOWNLOAD);
        MvcResult issued = mockMvc.perform(post("/api/tools/" + toolId + "/download-token")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"versionId\":\"" + versionId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String body = issued.getResponse().getContentAsString();
        String downloadToken = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        // 不带 Authorization，仅凭 ?token= 下载
        mockMvc.perform(get("/api/tools/" + toolId + "/download").param("token", downloadToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"));
    }

    /** ?token= 无效 / 已用 → 401 */
    @Test
    void invalidTokenParamGets401() throws Exception {
        mockMvc.perform(get("/api/tools/" + toolId + "/download").param("token", "bogus"))
                .andExpect(status().isUnauthorized());
    }
}
