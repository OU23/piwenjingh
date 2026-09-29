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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回收站接口层（B4 验收 A7 + 路由正确性）。
 *
 * <p>与 {@code ToolTrashTest}（业务语义）互补：本类只关心**接口行为** ——
 * 真实 401 / 403、路径映射是否命中正确的处理器、以及"删除 → 列表 → 恢复"的完整往返。
 * 只用工具主表行即可（不需要版本与物理文件），因此夹具极轻。
 */
@AutoConfigureMockMvc
class ToolTrashEndpointTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;

    private Long deptId;
    private Long toolId;

    @BeforeEach
    void setUpData() {
        DeptDO dept = new DeptDO();
        dept.setName("研发部");
        dept.setStatus(StatusValue.ENABLED);
        dept.setCreateTime(LocalDateTime.now());
        deptMapper.insert(dept);
        deptId = dept.getId();

        insertUser("deleter", deptId, PermissionCode.TOOL_DELETE);

        LocalDateTime now = LocalDateTime.now();
        ToolDO tool = new ToolDO();
        tool.setName("待删工具");
        tool.setIntro("简介");
        tool.setFileExt("zip");
        tool.setScope(Scope.DEPT);
        tool.setDeptId(deptId);
        tool.setUploaderId(999_999L);
        tool.setLatestVersion("v1.0");
        tool.setFileSize(0L);
        tool.setDownloadCount(0);
        tool.setDeleted(ToolService.NOT_DELETED);
        tool.setCreateTime(now);
        tool.setUpdateTime(now);
        tool.setLatestVersionId(0L);
        toolMapper.insert(tool);
        toolId = tool.getId();
    }

    /** A7：未登录 → 真 401；已登录但无 `tool:delete` → 真 403 */
    @Test
    void endpointsRequireDeletePermission() throws Exception {
        mockMvc.perform(delete("/api/tools/" + toolId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/tools/deleted")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/tools/batch-delete")).andExpect(status().isUnauthorized());

        String noPerm = tokenWith();   // 不带任何权限点
        mockMvc.perform(delete("/api/tools/" + toolId)
                        .header("Authorization", "Bearer " + noPerm))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tools/deleted")
                        .header("Authorization", "Bearer " + noPerm))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tools/batch-purge")
                        .header("Authorization", "Bearer " + noPerm)
                        .contentType("application/json").content("{\"ids\":[\"1\"]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/tools/deleted")
                        .header("Authorization", "Bearer " + noPerm))
                .andExpect(status().isForbidden());
    }

    /**
     * 路由钉住：`/api/tools/deleted` 必须命中**已删除列表**，
     * 不能被 {@code ToolController} 的 `/api/tools/{id}` 抢走当成工具 ID 解析（那会得到 1003）。
     */
    @Test
    void deletedListRouteHitsTrashListNotToolDetail() throws Exception {
        String token = tokenWith(PermissionCode.TOOL_DELETE);

        mockMvc.perform(get("/api/tools/deleted").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray());

        // 反向：非数字 ID 仍走详情并给出 1003（说明详情映射未被破坏）
        mockMvc.perform(get("/api/tools/abc").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(1003));
    }

    /** 接口层完整往返：删除 → 已删除列表有它 → 恢复 → 已删除列表为空 */
    @Test
    void deleteThenListThenRestore() throws Exception {
        String token = tokenWith(PermissionCode.TOOL_DELETE);

        mockMvc.perform(get("/api/tools/deleted").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(delete("/api/tools/" + toolId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/tools/deleted").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].name").value("待删工具"));

        mockMvc.perform(post("/api/tools/" + toolId + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/tools/deleted").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    /** 批量为空 / 非数组 → 1003（契约 §9.6）；非数组不得报 500 */
    @Test
    void invalidBatchPayloadReturns1003() throws Exception {
        String token = tokenWith(PermissionCode.TOOL_DELETE);

        mockMvc.perform(post("/api/tools/batch-delete")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"ids\":[]}"))
                .andExpect(jsonPath("$.code").value(1003));

        mockMvc.perform(post("/api/tools/batch-delete")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"ids\":\"not-an-array\"}"))
                .andExpect(jsonPath("$.code").value(1003));
    }

    /** 彻底删除不存在的 / 未软删的工具 → 忽略并成功（幂等，不误删线上工具） */
    @Test
    void purgeIgnoresNotDeletedTool() throws Exception {
        String token = tokenWith(PermissionCode.TOOL_DELETE);

        mockMvc.perform(post("/api/tools/batch-purge")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"ids\":[\"" + toolId + "\",\"999999\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        org.junit.jupiter.api.Assertions.assertNotNull(toolMapper.selectById(toolId),
                "未软删的工具不得被彻底删除（防误删线上工具）");
    }

    /** 清空空回收站 → 成功（契约：空回收站 → code = 0） */
    @Test
    void purgeAllOnEmptyTrashSucceeds() throws Exception {
        mockMvc.perform(delete("/api/tools/deleted")
                        .header("Authorization", "Bearer " + tokenWith(PermissionCode.TOOL_DELETE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
    }

    // ---------------- 夹具 ----------------

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

    private String tokenWith(String... perms) {
        return signToken(LoginUser.builder()
                .userId(999_999L).username("deleter").deptId(deptId)
                .permissions(Set.of(perms)).build());
    }
}
