package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.result.PageResult;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.dto.ToolBatchDTO;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.service.ToolQueryService;
import com.example.toolbox.tool.service.ToolService;
import com.example.toolbox.tool.service.ToolTrashService;
import com.example.toolbox.tool.service.ToolWriteService;
import com.example.toolbox.tool.dto.VersionSaveDTO;
import com.example.toolbox.tool.vo.ToolVO;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.entity.UserPermissionDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除、回收站与恢复（B4 验收 A4 / A5 / A6 / A7 / C3 / C4 / C5）。
 *
 * <p>鉴权类走完整过滤链（真实 401 / 403），业务语义走 Service（更快、更易定位）。
 * 彻底删除（物理文件）见 {@code ToolPurgeTest}。
 */
class ToolTrashTest extends IntegrationTestBase {

    @Autowired
    private ToolTrashService toolTrashService;
    @Autowired
    private ToolQueryService toolQueryService;
    @Autowired
    private ToolWriteService toolWriteService;
    @Autowired
    private ToolService toolService;
    @Autowired
    private StorageService storageService;

    private Long deptA;
    private Long deptB;
    private Long toolA;
    private Long toolB;
    private UserDO deptAUser;
    private UserDO adminUser;

    @BeforeEach
    void setUpData() {
        deptA = createDept("研发部");
        deptB = createDept("市场部");
        deptAUser = insertUser("zhangsan", deptA, PermissionCode.TOOL_DELETE, PermissionCode.TOOL_UPLOAD);
        adminUser = insertUser("admin", deptA, PermissionCode.TOOL_DELETE, PermissionCode.SYS_SETTING,
                PermissionCode.TOOL_UPLOAD);
        toolA = createTool("工具A", deptA, Scope.DEPT, "v1.0");
        toolB = createTool("工具B", deptB, Scope.DEPT, "v1.0");
    }

    // ---------------- A4 软删除 ----------------

    /** A4：软删除后从正常列表与检索消失、出现在已删除列表，且**记录仍在库**（非物理删除） */
    @Test
    void softDeleteMovesToolToTrashWithoutLosingRecord() {
        loginAs(deptAUser);

        assertEquals(1, normalList().getTotal(), "删除前正常列表应有 1 条");
        toolTrashService.softDelete(toolA);

        assertEquals(0, normalList().getTotal(), "删除后正常列表不得含该工具");
        PageResult<ToolVO> trash = toolTrashService.deletedPage(1, 20, null);
        assertEquals(1, trash.getTotal(), "已删除列表应含该工具");
        assertEquals(String.valueOf(toolA), trash.getList().get(0).getId());

        ToolDO raw = toolMapper.selectById(toolA);
        assertTrue(raw != null && raw.getDeleted() == ToolService.DELETED,
                "记录仍应在库且被标记删除（A4：不物理删除）");
        assertTrue(toolVersionMapper.selectCount(null) > 0,
                "版本记录必须保留 —— 软删除的版本行从未被动过，因此恢复成本为零");
    }

    /** A4：关键词检索也查不到已删除工具 */
    @Test
    void deletedToolNotSearchableByKeyword() {
        loginAs(deptAUser);
        toolTrashService.softDelete(toolA);

        assertEquals(0, toolQueryService.page(1, 20, "工具A", null, null, null, null, null)
                .getTotal(), "检索不得命中已删除工具");
    }

    /** C5：重复删除 → 幂等成功，不报错、不产生脏数据 */
    @Test
    void repeatedDeleteIsIdempotent() {
        loginAs(deptAUser);
        toolTrashService.softDelete(toolA);
        toolTrashService.softDelete(toolA);   // 第二次不得抛异常

        assertEquals(1, toolTrashService.deletedPage(1, 20, null).getTotal(), "不得产生重复记录");
        assertEquals(ToolService.DELETED, toolMapper.selectById(toolA).getDeleted());
    }

    // ---------------- A5 恢复 ----------------

    /** A5：恢复后回到原状态、从已删除列表消失，且**版本与下载都正常** */
    @Test
    void restoreBringsBackToolWithAllVersions() {
        loginAs(deptAUser);
        toolTrashService.softDelete(toolA);
        toolTrashService.restore(toolA);

        assertEquals(1, normalList().getTotal(), "恢复后应回到正常列表");
        assertEquals(0, toolTrashService.deletedPage(1, 20, null).getTotal(), "应从已删除列表消失");
        assertEquals(ToolService.NOT_DELETED, toolMapper.selectById(toolA).getDeleted());

        // 版本仍完整（软删除从未动过版本行）
        assertEquals(1, toolService.versions(toolA).size(), "版本列表应完整");
        // 已入库文件仍可读 → 下载可用
        String fileKey = toolVersionMapper.selectList(null).get(0).getFileKey();
        assertTrue(storageService.exists(fileKey), "物理文件不得被软删除影响");
    }

    /** A5：恢复后还能**追加版本**（工具回到完全可用的原状态） */
    @Test
    void restoredToolAcceptsNewVersion() {
        loginAs(deptAUser);
        toolTrashService.softDelete(toolA);
        toolTrashService.restore(toolA);

        String key = storageService.generateKey();
        save(key, 4);
        VersionSaveDTO dto = new VersionSaveDTO();
        dto.setVersion("v1.1");
        dto.setFileKey(key);
        dto.setFileName("a.zip");
        dto.setFileExt("ZIP");
        dto.setFileSize(4L);
        // 追加版本返回的是**新版本 ID**（契约 §9.4 出参），不是 fileKey
        String newVersionId = toolWriteService.addVersion(toolA, dto);
        assertEquals(2, toolService.versions(toolA).size(), "恢复后应能正常追加版本");
        assertEquals(String.valueOf(toolService.findVersion(Long.valueOf(newVersionId)).getId()),
                newVersionId, "返回值应是可查到的新版本 ID");
        assertEquals("v1.1", toolMapper.selectById(toolA).getLatestVersion(),
                "主表最新版本应同事务更新");
        assertEquals(key, toolService.findVersion(Long.valueOf(newVersionId)).getFileKey());
    }

    // ---------------- A6 已删除期间的拒绝 ----------------

    /** A6 / C4：已删除期间 —— 详情 / 下载 / 编辑 / 追加版本全部 2004 */
    @Test
    void deletedToolRejectsDetailDownloadEditAndAddVersion() {
        loginAs(deptAUser);
        toolTrashService.softDelete(toolA);

        assertEquals(Code.TOOL_DELETED, assertThrows(BusinessException.class,
                () -> toolService.detail(toolA)).getCode(), "详情应 2004");
        assertEquals(Code.TOOL_DELETED, assertThrows(BusinessException.class,
                () -> toolService.versions(toolA)).getCode(), "版本列表应 2004");
        assertEquals(Code.TOOL_DELETED, assertThrows(BusinessException.class,
                () -> toolService.requireVisibleTool(toolA, UserContext.get()))
                .getCode(), "下载前校验应 2004");
        assertEquals(Code.TOOL_DELETED, assertThrows(BusinessException.class,
                () -> toolWriteService.addVersion(toolA, new VersionSaveDTO())).getCode(),
                "追加版本应 2004");
    }

    // ---------------- C3 恢复异常 ----------------

    /** C3：恢复不存在的记录 / 未删除的记录 → 1004 */
    @Test
    void restoreInvalidTargetsReturn1004() {
        loginAs(deptAUser);

        assertEquals(Code.NOT_FOUND, assertThrows(BusinessException.class,
                () -> toolTrashService.restore(999_999L)).getCode(), "不存在 → 1004");
        assertEquals(Code.NOT_FOUND, assertThrows(BusinessException.class,
                () -> toolTrashService.restore(toolA)).getCode(), "未删除 → 1004");
    }

    /** 删除不存在的工具 → 1004 */
    @Test
    void deleteMissingToolReturns1004() {
        loginAs(deptAUser);
        assertEquals(Code.NOT_FOUND, assertThrows(BusinessException.class,
                () -> toolTrashService.softDelete(999_999L)).getCode());
    }

    // ---------------- 可见范围（R3 / N-04） ----------------

    /** 非管理员不得删除 / 恢复别的部门的工具 → 403 */
    @Test
    void crossDeptDeleteAndRestoreForbidden() {
        loginAs(deptAUser);
        assertEquals(Code.FORBIDDEN, assertThrows(BusinessException.class,
                () -> toolTrashService.softDelete(toolB)).getCode(), "跨部门删除应 403");

        // 管理员（持 sys:setting）不受部门隔离：可删别的部门
        loginAs(adminUser);
        toolTrashService.softDelete(toolB);
        assertEquals(ToolService.DELETED, toolMapper.selectById(toolB).getDeleted());

        // 管理员也可跨部门恢复
        toolTrashService.restore(toolB);
        assertEquals(ToolService.NOT_DELETED, toolMapper.selectById(toolB).getDeleted());
    }

    /** 已删除列表也受可见范围约束：管理员看全部部门，普通用户只看本部门 */
    @Test
    void deletedListRespectsVisibility() {
        loginAs(deptAUser);
        toolTrashService.softDelete(toolA);

        loginAs(adminUser);
        toolTrashService.softDelete(toolB);
        assertEquals(2, toolTrashService.deletedPage(1, 20, null).getTotal(),
                "全局管理员应看到全部部门的已删除工具（契约 §9.6 / N-04）");

        loginAs(deptAUser);
        assertEquals(1, toolTrashService.deletedPage(1, 20, null).getTotal(),
                "普通用户只应看到本部门的");
    }

    // ---------------- 批量软删除 ----------------

    /** 批量软删除：不存在 / 不可见 / 已删除的 ID 一律忽略，其余照常，整体成功 */
    @Test
    void batchDeleteIgnoresInvalidIdsAndSucceeds() {
        loginAs(deptAUser);
        ToolBatchDTO dto = new ToolBatchDTO();
        dto.setIds(List.of(String.valueOf(toolA), "999999",
                String.valueOf(toolB), String.valueOf(toolA)));
        toolTrashService.batchDelete(dto);   // 不得抛异常

        assertEquals(ToolService.DELETED, toolMapper.selectById(toolA).getDeleted(), "本部门的应被删除");
        assertEquals(ToolService.NOT_DELETED, toolMapper.selectById(toolB).getDeleted(),
                "不可见的（别的部门）应被忽略");
        assertEquals(1, toolTrashService.deletedPage(1, 20, null).getTotal(), "不得产生重复");
    }

    /** 批量为空 → 1003（契约 §9.6） */
    @Test
    void emptyBatchIdsRejectedWith1003() {
        loginAs(deptAUser);
        ToolBatchDTO dto = new ToolBatchDTO();
        dto.setIds(List.of());
        assertEquals(Code.PARAM_INVALID, assertThrows(BusinessException.class,
                () -> toolTrashService.batchDelete(dto)).getCode());
    }

    // ---------------- 夹具 ----------------

    private PageResult<ToolVO> normalList() {
        return toolQueryService.page(1, 20, null, null, null, null, null, null);
    }

    private void loginAs(UserDO u) {
        super.loginAs(u.getId(), u.getUsername(), u.getDeptId(), permissionCodesOf(u));
    }

    private String[] permissionCodesOf(UserDO u) {
        return userPermissionMapper.selectList(null).stream()
                .filter(p -> p.getUserId().equals(u.getId()))
                .map(UserPermissionDO::getPermissionCode).toArray(String[]::new);
    }

    private Long createDept(String name) {
        DeptDO d = new DeptDO();
        d.setName(name);
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);
        return d.getId();
    }

    private UserDO insertUser(String username, Long deptId, String... perms) {
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

    private Long createTool(String name, Long deptId, String scope, String version) {
        String key = storageService.generateKey();
        save(key, 4);
        LocalDateTime now = LocalDateTime.now();
        ToolDO tool = new ToolDO();
        tool.setName(name);
        tool.setIntro("简介");
        tool.setFileExt("zip");
        tool.setScope(scope);
        tool.setDeptId(deptId);
        tool.setUploaderId(deptAUser == null ? 999_999L : deptAUser.getId());
        tool.setLatestVersion(version);
        tool.setFileSize(4L);
        tool.setDownloadCount(0);
        tool.setDeleted(ToolService.NOT_DELETED);
        tool.setCreateTime(now);
        tool.setUpdateTime(now);
        tool.setLatestVersionId(0L);
        toolMapper.insert(tool);

        ToolVersionDO v = new ToolVersionDO();
        v.setToolId(tool.getId());
        v.setVersion(version);
        v.setFileKey(key);
        v.setFileName("a.zip");
        v.setFileExt("zip");
        v.setFileSize(4L);
        v.setUploaderId(999_999L);
        v.setDownloadCount(0);
        v.setCreateTime(now);
        toolVersionMapper.insert(v);

        tool.setLatestVersionId(v.getId());
        toolMapper.updateById(tool);
        return tool.getId();
    }

    private void save(String key, long size) {
        try {
            storageService.save(key, new ByteArrayInputStream(new byte[(int) size]), size);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
