package com.example.toolbox.excel;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测试范围：Excel 导入导出接口层（M-19 / 契约 §四 · §六 · §6.2）
 * 测试类型：接口（MockMvc，走完整过滤链：真 401 / 真 403）
 * 测试目的：锁死「6 个接口的权限」「文件流响应头（xlsx + 中文文件名双写）」「multipart 字段名 file」
 * 防回归问题：接口漏挂权限、模板响应不是 xlsx、Content-Disposition 缺 UTF-8 双写、
 *             非 .xlsx 未按 1003 整体失败
 * 关键不变量：
 *   - 6 个端点分别需 dept:manage / user:manage；未登录 → 401、无权限 → 403
 *   - 模板 / 导出：200 + xlsx Content-Type + `filename*=UTF-8''` 双写
 *   - 导入：multipart 字段名 `file`，返回统一响应体
 * 边界说明：不覆盖浏览器真实下载行为
 * 依赖与限制：上传文件由被测的 XlsxWriter 生成
 */
@AutoConfigureMockMvc
class ExcelEndpointTest extends IntegrationTestBase {

    private static final long ADMIN_ID = 999_999L;

    @Autowired
    private MockMvc mockMvc;

    private Long deptId;
    private String adminToken;
    private String noPermToken;

    @BeforeEach
    void setUpData() {
        DeptDO dept = new DeptDO();
        dept.setName("现有部门");
        dept.setStatus(StatusValue.ENABLED);
        dept.setCreateTime(LocalDateTime.now());
        deptMapper.insert(dept);
        deptId = dept.getId();

        adminToken = signToken(LoginUser.builder()
                .userId(ADMIN_ID).username("admin").deptId(deptId)
                .permissions(Set.of(PermissionCode.DEPT_MANAGE, PermissionCode.USER_MANAGE))
                .build());
        noPermToken = signToken(LoginUser.builder()
                .userId(ADMIN_ID + 1).username("nobody").deptId(deptId)
                .permissions(Set.of())
                .build());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private MockMultipartFile userFile(List<List<String>> rows) {
        byte[] bytes = XlsxWriter.toBytes(ExcelConstants.USER_SHEET,
                XlsxWriter.headOf(ExcelConstants.USER_HEAD), rows);
        return new MockMultipartFile("file", "users.xlsx", ExcelConstants.XLSX_CONTENT_TYPE, bytes);
    }

    // ---------------- 模板 / 导出：响应形态 ----------------

    @Test
    void templateDownloadHasXlsxHeadersAndFileName() throws Exception {
        mockMvc.perform(get("/api/depts/import-template").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("spreadsheetml.sheet")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, allOf(
                        containsString("attachment"),
                        containsString("filename*=UTF-8''"))));
    }

    @Test
    void userTemplateDownloadHasXlsxHeaders() throws Exception {
        mockMvc.perform(get("/api/users/import-template").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("spreadsheetml.sheet")));
    }

    @Test
    void exportReturnsXlsxStream() throws Exception {
        mockMvc.perform(get("/api/users/export").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("spreadsheetml.sheet")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("filename*=UTF-8''")));
    }

    // ---------------- 权限（真 401 / 真 403） ----------------

    @Test
    void endpointsRequireManagePermission() throws Exception {
        // 部门：模板 / 导入 / 导出
        mockMvc.perform(get("/api/depts/import-template")
                        .header(HttpHeaders.AUTHORIZATION, bearer(noPermToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/depts/export")
                        .header(HttpHeaders.AUTHORIZATION, bearer(noPermToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/depts/import")
                        .file(userFile(List.of(List.of("x", ""))))
                        .header(HttpHeaders.AUTHORIZATION, bearer(noPermToken)))
                .andExpect(status().isForbidden());

        // 用户：模板 / 导入 / 导出
        mockMvc.perform(get("/api/users/import-template")
                        .header(HttpHeaders.AUTHORIZATION, bearer(noPermToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/export")
                        .header(HttpHeaders.AUTHORIZATION, bearer(noPermToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/users/import")
                        .file(userFile(List.of(List.of("u001", "甲", "现有部门", "123456"))))
                        .header(HttpHeaders.AUTHORIZATION, bearer(noPermToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedGetsReal401() throws Exception {
        mockMvc.perform(get("/api/users/import-template"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/export"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------- 导入：HTTP 形态 ----------------

    @Test
    void userImportViaMultipartReturnsResult() throws Exception {
        mockMvc.perform(multipart("/api/users/import")
                        .file(userFile(List.of(List.of("u001", "甲", "现有部门", "123456"))))
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.successCount").value(1))
                .andExpect(jsonPath("$.data.errorFileBase64").value(nullValue()));
    }

    @Test
    void nonXlsxViaHttpFailsWith1003() throws Exception {
        MockMultipartFile csv = new MockMultipartFile("file", "a.csv", "text/csv", "x,y".getBytes());

        mockMvc.perform(multipart("/api/users/import")
                        .file(csv)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void templateContentIsReadableXlsx() throws Exception {
        byte[] body = mockMvc.perform(get("/api/users/import-template")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        // xlsx 为 ZIP 容器：魔数 PK
        assertEquals('P', body[0]);
        assertEquals('K', body[1]);
        assertTrue(body.length > 100, "模板内容不应为空");
    }
}
