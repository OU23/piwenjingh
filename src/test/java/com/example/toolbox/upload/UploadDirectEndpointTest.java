package com.example.toolbox.upload;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.storage.OssStorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.entity.UserPermissionDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 直传三端点的接口层（B14 验收 A14 · A17 · A1）。
 *
 * <p>走**完整过滤链**，验证真实 HTTP 状态码（401 / 403）；`OssStorageService` 为替身（不打真实 OSS）。
 * 关键守恒：`complete` **不得写任何业务表** —— 落库仍发生在契约 §九 创建工具 / 版本时。
 */
@AutoConfigureMockMvc
class UploadDirectEndpointTest extends IntegrationTestBase {

    private static final long MB = 1024L * 1024L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OssStorageService ossStorageService;

    private Long deptId;

    @BeforeEach
    void setUpDept() {
        DeptDO dept = new DeptDO();
        dept.setName("研发部");
        dept.setStatus(StatusValue.ENABLED);
        dept.setCreateTime(LocalDateTime.now());
        deptMapper.insert(dept);
        deptId = dept.getId();
    }

    // ---------------- A14 完成登记：零写库 ----------------

    @Test
    void completeReturnsMergeShapeAndWritesNoBusinessRows() throws Exception {
        String key = ownKey();
        when(ossStorageService.exists(key)).thenReturn(true);
        when(ossStorageService.size(key)).thenReturn(10 * MB);

        mockMvc.perform(post("/api/upload/direct/complete")
                        .header("Authorization", "Bearer " + uploaderToken())
                        .contentType("application/json")
                        .content("{\"objectKey\":\"" + key + "\",\"fileName\":\"demo.zip\",\"fileSize\":" + (10 * MB) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.fileKey").value(key))
                .andExpect(jsonPath("$.data.fileName").value("demo.zip"))
                .andExpect(jsonPath("$.data.fileSize").value(10 * MB));

        assertEquals(0L, toolMapper.selectCount(null), "complete 不得写工具表（落库在 §九）");
        assertEquals(0L, toolVersionMapper.selectCount(null), "complete 不得写版本表（落库在 §九）");
    }

    /** A15：OSS 异常经接口仍是 200 + 2006（非 500） */
    @Test
    void completeMapsOssFailureTo2006() throws Exception {
        when(ossStorageService.exists(anyString())).thenThrow(
                new com.example.toolbox.common.exception.BusinessException(
                        com.example.toolbox.common.result.Code.STORAGE_OSS_UNAVAILABLE, "对象存储暂不可用"));

        mockMvc.perform(post("/api/upload/direct/complete")
                        .header("Authorization", "Bearer " + uploaderToken())
                        .contentType("application/json")
                        .content("{\"objectKey\":\"" + ownKey() + "\",\"fileName\":\"demo.zip\",\"fileSize\":" + (10 * MB) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2006));
    }

    /** A11：伪造他人前缀 → 1003 */
    @Test
    void completeRejectsForeignPrefix() throws Exception {
        mockMvc.perform(post("/api/upload/direct/complete")
                        .header("Authorization", "Bearer " + uploaderToken())
                        .contentType("application/json")
                        .content("{\"objectKey\":\"tools/999999/0123456789abcdef0123456789abcdef.zip\","
                                + "\"fileName\":\"demo.zip\",\"fileSize\":" + (10 * MB) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    // ---------------- A1 模式查询 ----------------

    /** 测试 profile 的 storage.type 为 local（未配置）→ `server`；分片参数来自后端默认值 */
    @Test
    void modeReportsServerAndBackendDefaults() throws Exception {
        mockMvc.perform(get("/api/upload/mode")
                        .header("Authorization", "Bearer " + uploaderToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mode").value("server"))
                .andExpect(jsonPath("$.data.partSize").value(20971520))
                .andExpect(jsonPath("$.data.partSizeMin").value(5242880))
                .andExpect(jsonPath("$.data.partSizeMax").value(104857600))
                .andExpect(jsonPath("$.data.maxFileSize").value(10737418240L));
    }

    // ---------------- A17 鉴权不放松 ----------------

    @Test
    void allThreeEndpointsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/upload/mode")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/upload/direct/credentials")
                        .contentType("application/json").content("{\"fileName\":\"a.zip\",\"fileSize\":1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/upload/direct/complete")
                        .contentType("application/json")
                        .content("{\"objectKey\":\"k\",\"fileName\":\"a.zip\",\"fileSize\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allThreeEndpointsRequireUploadPermission() throws Exception {
        String token = token(user("zhangsan")); // 无 tool:upload

        mockMvc.perform(get("/api/upload/mode").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/upload/direct/credentials")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"fileName\":\"a.zip\",\"fileSize\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/upload/direct/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"objectKey\":\"k\",\"fileName\":\"a.zip\",\"fileSize\":1}"))
                .andExpect(status().isForbidden());
    }

    // ---------------- 内部 ----------------

    private String ownKey() {
        return "tools/" + deptId + "/0123456789abcdef0123456789abcdef.zip";
    }

    private String uploaderToken() {
        return token(user("zhangsan", PermissionCode.TOOL_UPLOAD), PermissionCode.TOOL_UPLOAD);
    }

    private UserDO user(String username, String... perms) {
        UserDO u = new UserDO();
        u.setUsername(username);
        u.setPassword("$2a$10$placeholder-shape-valid");
        u.setRealName("测试用户");
        u.setDeptId(deptId);
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

    private String token(UserDO u, String... perms) {
        return signToken(LoginUser.builder()
                .userId(u.getId()).username(u.getUsername()).realName(u.getRealName())
                .deptId(u.getDeptId()).permissions(Set.of(perms)).build());
    }
}
