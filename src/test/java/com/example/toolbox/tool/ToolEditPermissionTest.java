package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.service.ToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P15 / 契约 v1.15：`PUT /api/tools/{id}` **编辑判权收紧**（接口层，走完整过滤链）。
 *
 * <p>新口径：**上传者本人** 或 **同时持 `user:manage` + `user:grant`**（AND）；全局管理员
 * （`sys:setting`）短路放行；其余 → **真 403**（`code = 1002`）。
 *
 * <p>本类同时钉住一个**易回退点**：该端点的 `@RequiresPermission(tool:upload)` 注解**必须不存在**
 * —— 否则未持 `tool:upload` 的双权限管理员（A2）会被注解先拦掉（这正是熔断自检要复现的失败）。
 *
 * <p>夹具自清理由 {@link IntegrationTestBase} 的 {@code @AfterEach} 负责；权限快照取自令牌
 * （{@link LoginUser#getPermissions()}），因此**不需要**插权限表数据。
 */
@AutoConfigureMockMvc
class ToolEditPermissionTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    private Long deptA;
    private Long deptB;
    /** 工具所属部门（deptA）里的一名普通账号，作为"上传者本人" */
    private Long uploaderId;
    /** deptA / DEPT / uploader = uploaderId 的工具 */
    private Long ownToolId;
    /** deptB / PUBLIC 的工具（deptA 用户也可见 → 用于验证跨部门收紧） */
    private Long publicToolId;
    /** 已软删除的工具 */
    private Long deletedToolId;

    @BeforeEach
    void setUpData() {
        deptA = insertDept("研发部");
        deptB = insertDept("市场部");
        uploaderId = 1001L;

        ownToolId = insertTool("上传者的工具", deptA, Scope.DEPT, uploaderId, ToolService.NOT_DELETED);
        publicToolId = insertTool("他部门的公开工具", deptB, Scope.PUBLIC, 2002L, ToolService.NOT_DELETED);
        deletedToolId = insertTool("已删除工具", deptA, Scope.DEPT, uploaderId, 1);
        // 名称全局唯一用：与 ownToolId 同名（A10）
        insertTool("重名工具", deptB, Scope.PUBLIC, 2002L, ToolService.NOT_DELETED);
    }

    // ---------------- 放行 ----------------

    /** A1：**上传者本人**（不带任何权限点）编辑自己的工具 → 200 */
    @Test
    void uploaderWithoutAnyPermissionCanEditOwnTool() throws Exception {
        edit(ownToolId, token(uploaderId, deptA), "上传者的工具")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /**
     * A2：**双权限管理员**（`user:manage` + `user:grant`，**未持** `tool:upload`、也非上传者）
     * 编辑他人工具 → 200。★ 本用例是"端点注解已移除"的验收。
     */
    @Test
    void dualPermissionAdminWithoutUploadPermissionCanEditOthersTool() throws Exception {
        edit(ownToolId, token(3003L, deptA, PermissionCode.USER_MANAGE, PermissionCode.USER_GRANT), "上传者的工具")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /** A7：全局管理员（`sys:setting`，非上传者、不持双权限）→ 200（短路放行） */
    @Test
    void globalAdminIsShortCircuited() throws Exception {
        edit(ownToolId, token(4004L, deptA, PermissionCode.SYS_SETTING), "上传者的工具")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------------- 拒绝 ----------------

    /** A3：仅持 `tool:upload`、非上传者、同部门 → 真 403 / 1002 */
    @Test
    void uploadOnlyNonUploaderIsForbidden() throws Exception {
        edit(ownToolId, token(5005L, deptA, PermissionCode.TOOL_UPLOAD), "上传者的工具")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1002));
    }

    /** A4：仅持 `tool:upload`、跨部门编辑他部门 `PUBLIC` 工具 → 真 403 / 1002（原漏洞点） */
    @Test
    void uploadOnlyCrossDeptPublicToolIsForbidden() throws Exception {
        edit(publicToolId, token(5005L, deptA, PermissionCode.TOOL_UPLOAD), "他部门的公开工具")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1002));
    }

    /** A5：**只持** `user:manage`（缺 `user:grant`）→ 真 403 / 1002（AND 语义） */
    @Test
    void userManageOnlyIsForbidden() throws Exception {
        edit(ownToolId, token(6006L, deptA, PermissionCode.USER_MANAGE), "上传者的工具")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1002));
    }

    /** A6：**只持** `user:grant`（缺 `user:manage`）→ 真 403 / 1002（AND 语义） */
    @Test
    void userGrantOnlyIsForbidden() throws Exception {
        edit(ownToolId, token(7007L, deptA, PermissionCode.USER_GRANT), "上传者的工具")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1002));
    }

    // ---------------- 判定顺序与既有校验不受影响 ----------------

    /** A8：工具不存在 → 1004 */
    @Test
    void notFoundReturns1004() throws Exception {
        edit(9_999_999L, token(uploaderId, deptA), "随便")
                .andExpect(jsonPath("$.code").value(1004));
    }

    /** A9：已软删除工具 → 2004（判权顺序：存在 → 未删除 → 可见 → 可编辑） */
    @Test
    void deletedToolReturns2004() throws Exception {
        edit(deletedToolId, token(uploaderId, deptA), "已删除工具")
                .andExpect(jsonPath("$.code").value(2004));
    }

    /** A10：上传者改名撞已存在名称 → 1005（回归：收紧判权未影响既有唯一性校验） */
    @Test
    void duplicateNameByUploaderReturns1005() throws Exception {
        edit(ownToolId, token(uploaderId, deptA), "重名工具")
                .andExpect(jsonPath("$.code").value(1005));
    }

    // ---------------- 夹具 ----------------

    private Long insertDept(String name) {
        DeptDO d = new DeptDO();
        d.setName(name);
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);
        return d.getId();
    }

    private Long insertTool(String name, Long deptId, String scope, Long uploader, int deleted) {
        LocalDateTime now = LocalDateTime.now();
        ToolDO tool = new ToolDO();
        tool.setName(name);
        tool.setIntro("简介");
        tool.setFileExt("zip");
        tool.setScope(scope);
        tool.setDeptId(deptId);
        tool.setUploaderId(uploader);
        tool.setLatestVersion("v1.0");
        tool.setFileSize(4L);
        tool.setDownloadCount(0);
        tool.setDeleted(deleted);
        tool.setCreateTime(now);
        tool.setUpdateTime(now);
        tool.setLatestVersionId(0L);
        toolMapper.insert(tool);
        return tool.getId();
    }

    /** 令牌即权限快照来源：`ToolWriteService.requireEditable` 只读 `LoginUser.permissions`。 */
    private String token(Long userId, Long deptId, String... perms) {
        return signToken(LoginUser.builder()
                .userId(userId)
                .username("u" + userId)
                .realName("用户" + userId)
                .deptId(deptId)
                .permissions(Set.of(perms))
                .build());
    }

    private ResultActions edit(Long toolId, String token, String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"version\":\"v9.9\",\"intro\":\"改后简介\","
                + "\"fileExt\":\"zip\",\"scope\":\"PUBLIC\"}";
        return mockMvc.perform(put("/api/tools/" + toolId)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(body));
    }
}
