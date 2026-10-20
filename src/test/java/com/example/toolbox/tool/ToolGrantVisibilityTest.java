package com.example.toolbox.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.ScopeChecker;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolGrantDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.service.DownloadService;
import com.example.toolbox.tool.service.DownloadTokenService;
import com.example.toolbox.tool.service.ToolGrantService;
import com.example.toolbox.tool.service.ToolPurgeService;
import com.example.toolbox.tool.service.ToolQueryService;
import com.example.toolbox.tool.service.ToolService;
import com.example.toolbox.tool.dto.ToolNameCheckDTO;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.entity.UserPermissionDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具定向授权（M-17）—— 可见范围与边界（验收 A8 / A9 / A11 / A12 / C7 / C8）。
 *
 * <p>关键口径：授权只给"看得见 + 下载得了"，且只加在<b>只读路径</b>（列表 / 详情 / 版本列表 /
 * 下载 / 下载令牌）；写路径（canAppend / 编辑 / 删除 / 追加版本 / 已删除列表）一律不受影响。
 * 本类在真实 Spring + H2 上下文里逐一钉死这 5 处只读路径的放行与取消、写路径的不受影响、
 * 硬删除级联清理授权、以及软删除（回收站）口径。
 */
class ToolGrantVisibilityTest extends IntegrationTestBase {

    @Autowired
    private ToolGrantService toolGrantService;
    @Autowired
    private ToolService toolService;
    @Autowired
    private ToolQueryService toolQueryService;
    @Autowired
    private DownloadTokenService downloadTokenService;
    @Autowired
    private DownloadService downloadService;
    @Autowired
    private ToolPurgeService toolPurgeService;
    @Autowired
    private ScopeChecker scopeChecker;

    private Long deptA;
    private Long deptB;
    private Long operatorId;
    private Long viewerId;
    private Long toolId;

    @BeforeEach
    void setUpData() {
        deptA = insertDept("研发部");
        deptB = insertDept("市场部");
        operatorId = insertUser("admin", deptA, PermissionCode.USER_GRANT, PermissionCode.TOOL_DELETE).getId();
        viewerId = insertUser("viewer", deptB, PermissionCode.TOOL_DOWNLOAD).getId();
        toolId = insertTool("工具A", deptA);
    }

    private LoginUser viewerUser() {
        return LoginUser.builder()
                .userId(viewerId).username("viewer").deptId(deptB)
                .permissions(Set.of(PermissionCode.TOOL_DOWNLOAD)).build();
    }

    private void grantViewer() {
        // 用户侧保存：viewer → [toolId]（共用同一张表，工具侧同理）
        toolGrantService.saveToolsForUser(viewerId, List.of(String.valueOf(toolId)), operatorId);
    }

    // ---------------- A8：5 处只读路径成对放行 ----------------

    @Test
    void listShowsGrantedToolOnlyAfterGrant() {
        loginAs(viewerId, "viewer", deptB, PermissionCode.TOOL_DOWNLOAD);
        // 授权前：跨部门 DEPT 工具不可见
        assertTrue(pageNames().stream().noneMatch(n -> n.equals("工具A")), "授权前不应出现在列表");

        grantViewer();

        loginAs(viewerId, "viewer", deptB, PermissionCode.TOOL_DOWNLOAD);
        assertTrue(pageNames().stream().anyMatch(n -> n.equals("工具A")), "授权后应出现在列表");
    }

    @Test
    void detailAndVersionsVisibleOnlyAfterGrant() {
        loginAs(viewerId, "viewer", deptB, PermissionCode.TOOL_DOWNLOAD);
        assertThrows(BusinessException.class, () -> toolService.detail(toolId), "授权前详情应 403");
        assertThrows(BusinessException.class, () -> toolService.versions(toolId), "授权前版本列表应 403");

        grantViewer();

        loginAs(viewerId, "viewer", deptB, PermissionCode.TOOL_DOWNLOAD);
        assertEquals("工具A", toolService.detail(toolId).getName(), "授权后详情可见");
        assertFalse(toolService.versions(toolId).isEmpty(), "授权后版本列表可见");
    }

    @Test
    void downloadTokenIssuedOnlyAfterGrant() {
        loginAs(viewerId, "viewer", deptB, PermissionCode.TOOL_DOWNLOAD);
        assertThrows(BusinessException.class,
                () -> downloadTokenService.issue(toolId, null), "授权前下载令牌应 403");

        grantViewer();

        loginAs(viewerId, "viewer", deptB, PermissionCode.TOOL_DOWNLOAD);
        assertTrue(downloadTokenService.issue(toolId, null).getToken() != null, "授权后下载令牌可签发");
    }

    @Test
    void downloadPlanPassesVisibilityAfterGrant() {
        LoginUser viewer = viewerUser();
        // 授权前：不可见 → 403
        assertThrows(BusinessException.class,
                () -> downloadService.plan(toolId, null, viewer, false), "授权前下载应 403");

        grantViewer();
        // 授权后：可见性通过，仅因测试未落盘物理文件而报 2005（证明已越过授权闸门）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadService.plan(toolId, null, viewer, false));
        assertEquals(Code.FILE_KEY_INVALID, ex.getCode(), "授权后可见性应通过（此处仅因无物理文件报 2005）");
    }

    // ---------------- A8 + C7：取消授权后 5 处立即全部失效 ----------------

    @Test
    void revokeMakesAllPathsFail() {
        grantViewer();
        // 取消授权（清空）
        toolGrantService.saveToolsForUser(viewerId, List.of(), operatorId);

        loginAs(viewerId, "viewer", deptB, PermissionCode.TOOL_DOWNLOAD);
        assertThrows(BusinessException.class, () -> toolService.detail(toolId), "取消后详情应 403");
        assertTrue(pageNames().stream().noneMatch(n -> n.equals("工具A")), "取消后列表不应出现");
        assertThrows(BusinessException.class,
                () -> downloadTokenService.issue(toolId, null), "取消后下载令牌应 403");
        assertThrows(BusinessException.class,
                () -> downloadService.plan(toolId, null, viewerUser(), false), "取消后下载应 403");
    }

    // ---------------- A9：写路径不受授权影响 ----------------

    @Test
    void checkNameCanAppendUnaffectedByGrant() {
        grantViewer();
        loginAs(viewerId, "viewer", deptB, PermissionCode.TOOL_DOWNLOAD);
        // canAppend 走 canAccess（不含授权例外）：跨部门的授权工具仍不可追加版本
        ToolNameCheckDTO nameCheck = new ToolNameCheckDTO();
        nameCheck.setName("工具A");
        assertFalse(toolService.checkName(nameCheck).getCanAppend(), "授权不改变 canAppend");
    }

    @Test
    void editAndTrashNotGrantedByGrant() {
        grantViewer();
        LoginUser viewer = viewerUser();
        // 编辑 / 追加版本 / 删除 共用 canAccess（不含授权例外）
        assertFalse(scopeChecker.canAccess(toolAsScoped(), viewer), "授权用户仍不可编辑该跨部门工具");
        assertThrows(BusinessException.class,
                () -> toolService.requireVisibleTool(toolId, viewer), "编辑（requireVisibleTool）应 403");
        assertThrows(BusinessException.class,
                () -> toolService.requireVisibleToolIncludingDeleted(toolId, viewer),
                "已删除列表（requireVisibleToolIncludingDeleted）应 403");
    }

    @Test
    void trashListExcludesGrantedTool() {
        grantViewer();
        // 写路径列表（applyVisibility，不含授权例外）：授权用户看不到该工具
        LambdaQueryWrapper<ToolDO> w = toolService.applyVisibility(
                Wrappers.lambdaQuery(), ToolDO::getScope, ToolDO::getDeptId,
                ToolDO::getUploaderId, viewerUser());
        assertEquals(0, toolMapper.selectCount(w).intValue(), "已删除列表等写路径不应包含授权工具");
    }

    // ---------------- A11：硬删除级联清理授权 ----------------

    @Test
    void hardDeleteCascadeCleansGrant() {
        grantViewer();
        // 先软删（purge 只作用于已软删工具，防止误删线上工具）
        ToolDO tool = toolMapper.selectById(toolId);
        tool.setDeleted(ToolService.DELETED);
        toolMapper.updateById(tool);

        loginAs(operatorId, "admin", deptA, PermissionCode.TOOL_DELETE);
        toolPurgeService.purge(new com.example.toolbox.tool.dto.ToolBatchDTO() {{
            setIds(List.of(String.valueOf(toolId)));
        }});

        assertEquals(0, toolGrantMapper.selectCount(
                        Wrappers.<ToolGrantDO>lambdaQuery().eq(ToolGrantDO::getToolId, toolId)).intValue(),
                "彻底删除后该工具的授权记录应被清理");
        assertTrue(toolMapper.selectById(toolId) == null, "工具主记录应被彻底删除");
    }

    // ---------------- A12 / C8：软删除（回收站）口径 ----------------

    @Test
    void softDeletedToolGrantListedButNotDownloadable() {
        // 软删但不彻底删
        ToolDO tool = toolMapper.selectById(toolId);
        tool.setDeleted(ToolService.DELETED);
        toolMapper.updateById(tool);

        grantViewer();
        // 回收站内工具：授权仍返回（tb_tool 行存在）
        assertTrue(toolGrantService.listToolsForUser(viewerId).stream()
                .anyMatch(t -> t.getId().equals(String.valueOf(toolId))), "软删工具仍返回授权记录");

        // 但不可下载：requireVisibleForRead 命中"已删除" → 2004（与 B5 口径一致）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadService.plan(toolId, null, viewerUser(), false));
        assertEquals(Code.TOOL_DELETED, ex.getCode(), "软删工具不可下载（2004）");
    }

    // ---------------- 夹具 ----------------

    private ToolDO toolAsScoped() {
        return toolMapper.selectById(toolId);
    }

    private List<String> pageNames() {
        return toolQueryService.page(1, 20, null, null, null, null, null, null)
                .getList().stream().map(t -> t.getName()).toList();
    }

    private Long insertDept(String name) {
        DeptDO d = new DeptDO();
        d.setName(name);
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);
        return d.getId();
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

    private Long insertTool(String name, Long deptId) {
        LocalDateTime now = LocalDateTime.now();
        ToolDO tool = new ToolDO();
        tool.setName(name);
        tool.setIntro("简介");
        tool.setFileExt("zip");
        tool.setScope(Scope.DEPT);
        tool.setDeptId(deptId);
        tool.setUploaderId(operatorId == null ? 1L : operatorId);
        tool.setLatestVersion("v1.0");
        tool.setFileSize(8L);
        tool.setDownloadCount(0);
        tool.setDeleted(ToolService.NOT_DELETED);
        tool.setCreateTime(now);
        tool.setUpdateTime(now);
        tool.setLatestVersionId(0L);
        toolMapper.insert(tool);

        ToolVersionDO v = new ToolVersionDO();
        v.setToolId(tool.getId());
        v.setVersion("v1.0");
        v.setFileKey("fake-key-" + name);
        v.setFileName(name + ".zip");
        v.setFileExt("zip");
        v.setFileSize(8L);
        v.setUploaderId(tool.getUploaderId());
        v.setDownloadCount(0);
        v.setCreateTime(now);
        toolVersionMapper.insert(v);

        tool.setLatestVersionId(v.getId());
        toolMapper.updateById(tool);
        return tool.getId();
    }
}
