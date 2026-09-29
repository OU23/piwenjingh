package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.JwtProvider;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.service.DownloadService;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.entity.UserPermissionDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B5 T2 验证：下载链路「资源已不存在」→ 404（契约 v1.11）。
 *
 * <p>覆盖：状态码矩阵（GET / HEAD / ?token= 通道）、探测相位（exists 优先 → 2005 而非 2003）、
 * 下载链路码值收敛（1004 → 2004）。HEAD 不写 body（RFC 9110）。
 *
 * <p>独立文件（计划的 T3 要求），不改动既有断言的归属；既有 DownloadEndpointTest /
 * DownloadServiceTest 中受 T2 行为变更影响的断言已在原文件同步更新。
 */
@AutoConfigureMockMvc
class DownloadResourceGoneTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private StorageService storageService;
    @Autowired
    private DownloadService downloadService;

    private Long deptId;
    private Long toolId;
    private Long versionId;
    private UserDO user;
    private byte[] content;

    @BeforeEach
    void setUpData() {
        DeptDO d = new DeptDO();
        d.setName("T2研发部");
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);
        deptId = d.getId();

        user = insertUser("t2zhang", deptId, PermissionCode.TOOL_DOWNLOAD);

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
        tool.setName("T2资源不存在工具");
        tool.setIntro("简介");
        tool.setFileExt("bin");
        tool.setScope(Scope.PUBLIC);
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
        v.setFileName("t2.bin");
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
        u.setRealName("T2测试用户");
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

    private LoginUser currentUser() {
        return LoginUser.builder().userId(user.getId()).username(user.getUsername()).deptId(deptId)
                .permissions(Set.of(PermissionCode.TOOL_DOWNLOAD)).build();
    }

    /** GET 下载：不存在的工具 → 404 + 2004（下载链路收敛 1004→2004） */
    @Test
    void missingToolReturns404With2004() throws Exception {
        String token = tokenFor(user, PermissionCode.TOOL_DOWNLOAD);
        mockMvc.perform(get("/api/tools/999999/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2004));
    }

    /** GET 下载：已删除工具 → 404 + 2004 */
    @Test
    void deletedToolReturns404With2004() throws Exception {
        ToolDO tool = toolMapper.selectById(toolId);
        tool.setDeleted(1);
        toolMapper.updateById(tool);

        String token = tokenFor(user, PermissionCode.TOOL_DOWNLOAD);
        mockMvc.perform(get("/api/tools/" + toolId + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2004));
    }

    /** GET 下载：版本不存在 → 404 + 2004（resolveVersion 收敛） */
    @Test
    void versionNotFoundReturns404With2004() throws Exception {
        String token = tokenFor(user, PermissionCode.TOOL_DOWNLOAD);
        mockMvc.perform(get("/api/tools/" + toolId + "/download")
                        .header("Authorization", "Bearer " + token)
                        .param("versionId", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2004));
    }

    /** GET 下载：物理文件缺失 → 404 + 2005（必须 2005，而非 2003） */
    @Test
    void physicalFileMissingReturns404With2005() throws Exception {
        ToolVersionDO v = toolVersionMapper.selectById(versionId);
        storageService.delete(v.getFileKey());   // 模拟物理文件缺失

        String token = tokenFor(user, PermissionCode.TOOL_DOWNLOAD);
        mockMvc.perform(get("/api/tools/" + toolId + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2005));
    }

    /** HEAD：资源已不存在 → 404，且**不写 body**（RFC 9110） */
    @Test
    void headResourceGoneReturns404NoBody() throws Exception {
        ToolVersionDO v = toolVersionMapper.selectById(versionId);
        storageService.delete(v.getFileKey());   // 物理文件缺失

        String token = tokenFor(user, PermissionCode.TOOL_DOWNLOAD);
        MvcResult result = mockMvc.perform(head("/api/tools/" + toolId + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andReturn();

        assertTrue(result.getResponse().getContentAsByteArray().length == 0,
                "HEAD 404 不应携带响应体（RFC 9110）");
    }

    /** ?token= 通道：资源已不存在（物理文件缺失）→ 404（两条鉴权通道一致） */
    @Test
    void tokenChannelResourceGoneReturns404() throws Exception {
        // 删除物理文件（工具/版本仍在），模拟资源已不存在
        ToolVersionDO v = toolVersionMapper.selectById(versionId);
        storageService.delete(v.getFileKey());

        String jwt = tokenFor(user, PermissionCode.TOOL_DOWNLOAD);
        MvcResult issued = mockMvc.perform(post("/api/tools/" + toolId + "/download-token")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = issued.getResponse().getContentAsString();
        String downloadToken = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/tools/" + toolId + "/download").param("token", downloadToken))
                .andExpect(status().isNotFound());
    }

    /** 探测相位：物理文件缺失在 Service 层即收敛为 2005（而非 2003 存储不可用） */
    @Test
    void physicalFileMissingMapsTo2005Not2003() throws IOException {
        ToolVersionDO v = toolVersionMapper.selectById(versionId);
        storageService.delete(v.getFileKey());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadService.plan(toolId, null, currentUser(), false));
        assertEquals(Code.FILE_KEY_INVALID, ex.getCode(),
                "物理文件缺失必须映射为 2005（FILE_KEY_INVALID），而非 2003 存储不可用");
    }
}
