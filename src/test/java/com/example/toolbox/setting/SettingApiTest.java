package com.example.toolbox.setting;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.SettingKey;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.util.PathUtils;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.JwtProvider;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.setting.service.SettingService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.entity.UserPermissionDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局设置接口（B4 验收 A1 / A2 / C1 / C2 / C6）。
 *
 * <p>走完整过滤链，验证**真实 HTTP 状态码**（401 / 403）与错误码（1003 / 2003）。
 * A3（设置生效）与存储根路径落盘见 {@code SettingEffectiveTest}。
 */
@AutoConfigureMockMvc
class SettingApiTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private SettingService settingService;

    @TempDir
    Path tempDir;

    /** 用户表 dept_id 为 NOT NULL，夹具必须先建一个部门 */
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

    // ---------------- A1 读取 ----------------

    /** A1：登录即可读；返回单文件上限；**不含任何账号 / 密钥类字段** */
    @Test
    void readReturnsLimitWithoutSensitiveFields() throws Exception {
        String body = mockMvc.perform(get("/api/settings")
                        .header("Authorization", "Bearer " + token(insertUser("zhangsan"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.maxFileSize").value(10737418240L))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("password"), "不得返回口令类字段：" + body);
        assertFalse(body.contains("secret"), "不得返回密钥类字段：" + body);
    }

    /** A1 / N-03：`storageRoot` 只对 `sys:setting` 权限者返回，其他人拿 null */
    @Test
    void storageRootVisibleOnlyToSettingPermission() throws Exception {
        settingService.putValue(SettingKey.STORAGE_ROOT, "D:/toolbox/files");

        // 普通用户（无 sys:setting）→ 字段存在但为 null（契约 §十 / N-03 原文即"返回 null"）
        mockMvc.perform(get("/api/settings")
                        .header("Authorization", "Bearer " + token(insertUser("zhangsan"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storageRoot").value(org.hamcrest.Matchers.nullValue()));

        // 管理员（持 sys:setting）→ 真实路径
        mockMvc.perform(get("/api/settings")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storageRoot").value("D:/toolbox/files"));
    }

    /** 未登录读设置 → 真 401 */
    @Test
    void readWithoutLoginGets401() throws Exception {
        mockMvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
    }

    // ---------------- A2 保存 ----------------

    /** A2：无 `sys:setting` 权限保存 → 真 403，且**值未被改动** */
    @Test
    void saveWithoutPermissionGets403AndChangesNothing() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .header("Authorization", "Bearer " + token(insertUser("zhangsan")))
                        .contentType("application/json")
                        .content("{\"maxFileSize\":123}"))
                .andExpect(status().isForbidden());

        assertEquals(10737418240L, settingService.getMaxFileSize(), "越权请求不得改动设置");
    }

    /** A2：支持只提交一项（另一项保持原值） */
    @Test
    void saveSingleFieldKeepsOtherUntouched() throws Exception {
        settingService.putValue(SettingKey.STORAGE_ROOT, "D:/keep/me");

        mockMvc.perform(put("/api/settings")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"maxFileSize\":2048}"))
                .andExpect(status().isOk());

        assertEquals(2048L, settingService.getMaxFileSize(), "上限应更新");
        assertEquals("D:/keep/me", settingService.getStorageRoot(), "未提交的项不得被清空");
    }

    /** 保存可用路径 → 成功，并按 v1.5 路径约定**归一化为正斜杠** */
    @Test
    void saveUsablePathSucceedsAndNormalizesSlashes() throws Exception {
        String raw = tempDir.toAbsolutePath().toString();
        String windowsStyle = raw.replace('/', '\\');   // 客户端可能传反斜杠

        mockMvc.perform(put("/api/settings")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"storageRoot\":\"" + escape(windowsStyle) + "\"}"))
                .andExpect(status().isOk());

        String saved = settingService.getStorageRoot();
        assertFalse(saved.contains("\\"), "应归一化为正斜杠（契约 §十 v1.5），实际：" + saved);
        assertTrue(Files.isDirectory(Path.of(saved)), "保存的应仍是同一个真实目录：" + saved);
    }

    // ---------------- C1 / C2 / C6 边界 ----------------

    /** C1：路径不存在 → 2003 且说明原因，**不保存** */
    @Test
    void nonexistentPathRejectedWith2003AndReason() throws Exception {
        Path missing = tempDir.resolve("not-exist-dir");
        mockMvc.perform(put("/api/settings")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"storageRoot\":\"" + escape(missing.toString()) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2003))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不存在")));

        assertNull(settingService.getStorageRoot(), "校验失败不得写入设置（C1：不误报成功）");
    }

    /** C1：路径是文件而不是目录 → 2003 且说明原因 */
    @Test
    void pathPointingToFileRejectedWith2003() throws Exception {
        Path file = tempDir.resolve("i-am-a-file.txt");
        Files.writeString(file, "x");

        mockMvc.perform(put("/api/settings")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"storageRoot\":\"" + escape(file.toString()) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2003))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不是目录")));

        assertNull(settingService.getStorageRoot(), "校验失败不得写入设置");
    }

    /** C2：上限传 0 / 负数 → 1003 */
    @Test
    void nonPositiveLimitRejectedWith1003() throws Exception {
        String token = adminToken();   // 只建一次：夹具里账号唯一键不允许重复插入同名用户
        for (String body : new String[]{"{\"maxFileSize\":0}", "{\"maxFileSize\":-1}"}) {
            mockMvc.perform(put("/api/settings")
                            .header("Authorization", "Bearer " + token)
                            .contentType("application/json").content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1003));
        }
        assertEquals(10737418240L, settingService.getMaxFileSize(), "非法值不得写入");
    }

    /** C2：上限传非数字 → 1003（**不是 500** —— 客户端输入错误必须报 1003） */
    @Test
    void nonNumericLimitRejectedWith1003NotServerError() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"maxFileSize\":\"abc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    /** C6：两项都不提交 → 1003 明确拒绝（不静默成功） */
    @Test
    void emptyPayloadRejectedWith1003() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    /** 空字符串路径 → 1003（不是"改成空路径"） */
    @Test
    void blankStorageRootRejected() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"storageRoot\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    /** 路径归一化工具：正斜杠 / 盘符根 / 尾斜杠 / null 边界 */
    @Test
    void pathNormalizationRules() {
        assertEquals("D:/toolbox/files", PathUtils.normalizeSlashes("D:\\toolbox\\files"));
        assertEquals("D:/", PathUtils.normalizeSlashes("D:\\"), "盘符根不得被裁成 D:");
        assertEquals("/data/x", PathUtils.normalizeSlashes("/data/x/"), "尾部斜杠应去掉");
        assertEquals("/", PathUtils.normalizeSlashes("/"));
        assertEquals("/data/x", PathUtils.normalizeSlashes("  /data/x  "));
        assertEquals("", PathUtils.normalizeSlashes("   "));
        assertNull(PathUtils.normalizeSlashes(null));
    }

    // ---------------- 内部 ----------------

    private String adminToken() {
        // 注意：JWT 里的权限点来自签发时的 claims（由过滤器写入 UserContext），
        // 与库里 tb_user_permission 无关 —— 因此必须显式带上 sys:setting，
        // 否则会被 PermissionInterceptor 判成 403（这不是生产缺陷，是夹具少了权限）
        return token(insertUser("admin", PermissionCode.SYS_SETTING), PermissionCode.SYS_SETTING);
    }

    private String escape(String path) {
        return path.replace("\\", "\\\\");
    }

    private UserDO insertUser(String username, String... perms) {
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
