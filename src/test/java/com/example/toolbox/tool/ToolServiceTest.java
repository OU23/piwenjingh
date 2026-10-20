package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.result.PageResult;
import com.example.toolbox.dept.dto.DeptSaveDTO;
import com.example.toolbox.dept.service.DeptService;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tag.dto.TagSaveDTO;
import com.example.toolbox.tag.service.TagService;
import com.example.toolbox.tool.dto.ToolNameCheckDTO;
import com.example.toolbox.tool.dto.ToolSaveDTO;
import com.example.toolbox.tool.dto.ToolUpdateDTO;
import com.example.toolbox.tool.dto.VersionSaveDTO;
import com.example.toolbox.tool.service.ToolQueryService;
import com.example.toolbox.tool.service.ToolService;
import com.example.toolbox.tool.service.ToolWriteService;
import com.example.toolbox.tool.vo.ToolDetailVO;
import com.example.toolbox.tool.vo.ToolNameCheckVO;
import com.example.toolbox.tool.vo.ToolVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具 / 版本 / 检索 / 标签（B3-T6 / T7 / T8）。
 * 覆盖契约 §9.4 的名称唯一、版本唯一、fileKey 校验、归属部门取登录用户、
 * canAppend 可见性分支（N-01）、fileExt 小写（N-07）、可见范围过滤。
 */
class ToolServiceTest extends IntegrationTestBase {

    @Autowired
    private ToolService toolService;
    @Autowired
    private ToolWriteService toolWriteService;
    @Autowired
    private ToolQueryService toolQueryService;
    @Autowired
    private TagService tagService;
    @Autowired
    private DeptService deptService;
    @Autowired
    private StorageService storageService;

    private Long deptA;
    private Long deptB;

    /** 操作人 ID 用极大值，避免与 H2 自增主键碰撞 */
    private static final long ADMIN_ID = 999_999L;

    @BeforeEach
    void setUpData() {
        // 先建部门（建部门本身用任意上下文即可），再用**真实部门 ID** 作为登录上下文
        deptA = createDept("研发部");
        deptB = createDept("市场部");
        loginAs(ADMIN_ID, "admin", deptA, PermissionCode.TOOL_UPLOAD, PermissionCode.TOOL_DOWNLOAD,
                PermissionCode.DEPT_MANAGE, PermissionCode.TAG_MANAGE, PermissionCode.SYS_SETTING);
    }

    private Long createDept(String name) {
        // 建部门需要登录上下文（审计取操作人）；部门归属此处无关，用占位 ID
        loginAs(ADMIN_ID, "admin", null, PermissionCode.DEPT_MANAGE);
        DeptSaveDTO d = new DeptSaveDTO();
        d.setName(name);
        return Long.valueOf(deptService.create(d));
    }

    /** 造一个真实的存储文件，返回 fileKey */
    private String putFile(long size) {
        String key = storageService.generateKey();
        try {
            storageService.save(key, new ByteArrayInputStream(new byte[(int) size]), size);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return key;
    }

    private ToolSaveDTO saveDto(String name, String version, String scope, String fileKey) {
        ToolSaveDTO dto = new ToolSaveDTO();
        dto.setName(name);
        dto.setVersion(version);
        dto.setIntro("简介");
        dto.setReadme("说明");
        dto.setFileExt("py");
        dto.setPlatform("Windows");
        dto.setDependency("无");
        dto.setScope(scope);
        dto.setFileKey(fileKey);
        dto.setFileName("demo.py");
        dto.setFileSize(16L);
        return dto;
    }

    /** 新建工具：归属部门取登录用户，且返回 toolId / versionId */
    @Test
    void createTakesDeptFromLoginUserAndReturnsIds() {
        String key = putFile(16);
        Map<String, String> ids = toolWriteService.create(saveDto("工具A", "v1.0", Scope.DEPT, key));

        assertNotNull(ids.get("toolId"));
        assertNotNull(ids.get("versionId"));

        ToolDetailVO detail = toolService.detail(Long.valueOf(ids.get("toolId")));
        assertEquals("工具A", detail.getName());
        assertEquals("v1.0", detail.getLatestVersion());
        assertEquals(String.valueOf(deptA), detail.getDeptId(),
                "归属部门应取登录用户所在部门，不接受前端传入");
        assertEquals("py", detail.getFileExt(), "fileExt 应统一小写");
    }

    /** 名称全局唯一：重复 → 1005 */
    @Test
    void duplicateNameRejected() {
        toolWriteService.create(saveDto("工具A", "v1.0", Scope.DEPT, putFile(16)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolWriteService.create(saveDto("工具A", "v2.0", Scope.DEPT, putFile(16))));
        assertEquals(Code.CONFLICT, ex.getCode(), "重名应返回 1005");
    }

    /** check-name：无同名时 existed=false 且 canAppend=false */
    @Test
    void checkNameWhenNoDuplicate() {
        ToolNameCheckDTO dto = new ToolNameCheckDTO();
        dto.setName("不存在的工具");

        ToolNameCheckVO vo = toolService.checkName(dto);
        assertFalse(vo.getExisted());
        assertNull(vo.getToolId());
        assertNull(vo.getLatestVersion());
        assertFalse(vo.getCanAppend(), "无同名时 canAppend 必须为 false（契约 §9.4）");
    }

    /** check-name：同名且可见（本部门）→ canAppend=true */
    @Test
    void checkNameSameDeptCanAppend() {
        Map<String, String> ids = toolWriteService.create(saveDto("工具A", "v1.2", Scope.DEPT, putFile(16)));

        ToolNameCheckDTO dto = new ToolNameCheckDTO();
        dto.setName("工具A");
        ToolNameCheckVO vo = toolService.checkName(dto);

        assertTrue(vo.getExisted());
        assertEquals(ids.get("toolId"), vo.getToolId());
        assertEquals("v1.2", vo.getLatestVersion());
        assertTrue(vo.getCanAppend(), "本部门工具应可追加版本");
    }

    /**
     * check-name：**v1.21 行为变更** —— 名称唯一性收窄到上传人。
     * 他部门（= 他人）的同名工具**不再命中**：existed=false，前端走正常新建。
     * （旧断言为"名称全局唯一，他部门也能查到"，随契约 v1.21 废弃。）
     */
    @Test
    void checkNameOtherUploaderSameNameIsIgnored() {
        // 先用 A 部门身份建一个 DEPT 工具
        toolWriteService.create(saveDto("工具A", "v1.0", Scope.DEPT, putFile(16)));

        // 切到 B 部门用户（不同上传人）
        Long userB = 888_888L;
        loginAs(userB, "userB", deptB, PermissionCode.TOOL_UPLOAD);

        ToolNameCheckDTO dto = new ToolNameCheckDTO();
        dto.setName("工具A");
        ToolNameCheckVO vo = toolService.checkName(dto);

        assertFalse(vo.getExisted(), "v1.21：别人的同名工具不影响我的名称判定");
        assertFalse(vo.getCanAppend(), "无同名时 canAppend=false");
        assertFalse(vo.getInTrash(), "未命中回收站");
    }

    /**
     * check-name：**v1.21 行为变更** —— 他人上传的 PUBLIC 同名工具同样不命中。
     * （旧断言为"跨部门可追加版本"，随契约 v1.21 废弃：同名转版本只在同一上传人内发生。）
     */
    @Test
    void checkNamePublicToolByOtherUploaderIsIgnored() {
        toolWriteService.create(saveDto("公共工具", "v1.0", Scope.PUBLIC, putFile(16)));

        loginAs(888_888L, "userB", deptB, PermissionCode.TOOL_UPLOAD);

        ToolNameCheckDTO dto = new ToolNameCheckDTO();
        dto.setName("公共工具");
        ToolNameCheckVO vo = toolService.checkName(dto);

        assertFalse(vo.getExisted(), "v1.21：同名判定按上传人，不按可见范围");
        assertFalse(vo.getCanAppend(), "无同名时 canAppend=false");
    }

    /** 追加版本：同一工具内版本号唯一 → 1005 */
    @Test
    void duplicateVersionRejected() {
        Map<String, String> ids = toolWriteService.create(saveDto("工具A", "v1.0", Scope.DEPT, putFile(16)));
        Long toolId = Long.valueOf(ids.get("toolId"));

        VersionSaveDTO v = new VersionSaveDTO();
        v.setVersion("v1.0");
        v.setFileKey(putFile(16));
        v.setFileName("demo.py");
        v.setFileExt("py");
        v.setFileSize(16L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolWriteService.addVersion(toolId, v));
        assertEquals(Code.CONFLICT, ex.getCode(), "版本号重复应返回 1005");
    }

    /** 追加版本成功：最新版本信息同事务更新，版本列表倒序 */
    @Test
    void addVersionUpdatesLatestAndListsDesc() {
        Map<String, String> ids = toolWriteService.create(saveDto("工具A", "v1.0", Scope.DEPT, putFile(16)));
        Long toolId = Long.valueOf(ids.get("toolId"));

        VersionSaveDTO v = new VersionSaveDTO();
        v.setVersion("v2.0");
        v.setFileKey(putFile(32));
        v.setFileName("demo2.py");
        v.setFileExt("PY");               // 大写，应被归一化为小写
        v.setFileSize(32L);
        toolWriteService.addVersion(toolId, v);

        ToolDetailVO detail = toolService.detail(toolId);
        assertEquals("v2.0", detail.getLatestVersion(), "最新版本号应更新");
        assertEquals(32L, detail.getFileSize(), "冗余文件大小应更新");
        assertEquals(2, detail.getVersions().size(), "应有 2 个版本");

        // 版本列表按上传时间倒序：最新在前
        assertEquals("v2.0", detail.getVersions().get(0).getVersion());
        assertTrue(detail.getVersions().get(0).getIsLatest(), "首项应标记为最新");
        assertFalse(detail.getVersions().get(1).getIsLatest());
        assertEquals("py", detail.getVersions().get(0).getFileExt(), "fileExt 应统一小写（N-07）");
    }

    /** fileKey 无效（不存在）→ 2005 */
    @Test
    void invalidFileKeyRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolWriteService.create(saveDto("工具A", "v1.0", Scope.DEPT, "no-such-key")));
        assertEquals(Code.FILE_KEY_INVALID, ex.getCode(), "无效 fileKey 应返回 2005");
    }

    /** fileKey 已被其他版本占用 → 2005（同标识不可被两个版本复用） */
    @Test
    void reusedFileKeyRejected() {
        String key = putFile(16);
        toolWriteService.create(saveDto("工具A", "v1.0", Scope.DEPT, key));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolWriteService.create(saveDto("工具B", "v1.0", Scope.DEPT, key)));
        assertEquals(Code.FILE_KEY_INVALID, ex.getCode(), "复用 fileKey 应返回 2005");
    }

    /** 可见范围非法 → 1003 */
    @Test
    void invalidScopeRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolWriteService.create(saveDto("工具A", "v1.0", "EVERYONE", putFile(16))));
        assertEquals(Code.PARAM_INVALID, ex.getCode());
    }

    /** 编辑元数据：可改名、改可见范围与标签；文件与部门不变 */
    @Test
    void updateChangesMetadataOnly() {
        Map<String, String> ids = toolWriteService.create(saveDto("工具A", "v1.0", Scope.DEPT, putFile(16)));
        Long toolId = Long.valueOf(ids.get("toolId"));

        ToolUpdateDTO dto = new ToolUpdateDTO();
        dto.setName("工具A改名");
        dto.setVersion("v1.0");
        dto.setIntro("新简介");
        dto.setReadme("新说明");
        dto.setFileExt("zip");
        dto.setPlatform("Linux");
        dto.setDependency("需 JDK17");
        dto.setScope(Scope.PUBLIC);
        toolWriteService.update(toolId, dto);

        ToolDetailVO detail = toolService.detail(toolId);
        assertEquals("工具A改名", detail.getName());
        assertEquals("新简介", detail.getIntro());
        // v1.23：编辑**忽略** fileExt（本期不改格式）—— 传入的 zip 不生效，仍为创建时的 py
        assertEquals("py", detail.getFileExt(), "编辑不应改变文件格式");
        assertEquals(Scope.PUBLIC, detail.getScope());
        assertEquals(String.valueOf(deptA), detail.getDeptId(), "编辑不应改变归属部门");
        assertEquals(1, detail.getVersions().size(), "编辑不应新增版本");
    }

    /** 编辑时改名撞已有名称 → 1005；改成自己原名不算冲突 */
    @Test
    void updateRenameConflict() {
        Map<String, String> idsA = toolWriteService.create(saveDto("工具A", "v1.0", Scope.DEPT, putFile(16)));
        toolWriteService.create(saveDto("工具B", "v1.0", Scope.DEPT, putFile(16)));

        Long toolA = Long.valueOf(idsA.get("toolId"));
        ToolUpdateDTO dto = new ToolUpdateDTO();
        dto.setName("工具B");
        dto.setVersion("v1.0");
        dto.setIntro("简介");
        dto.setFileExt("py");
        dto.setScope(Scope.DEPT);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolWriteService.update(toolA, dto));
        assertEquals(Code.CONFLICT, ex.getCode(), "改名撞名应返回 1005");

        // 改成自己原名：不报错
        dto.setName("工具A");
        toolWriteService.update(toolA, dto);
    }

    /** 他部门不可见 → 详情 403、追加版本 403（P0 数据隔离） */
    @Test
    void otherDeptCannotAccessDeptTool() {
        Map<String, String> ids = toolWriteService.create(saveDto("私密工具", "v1.0", Scope.DEPT, putFile(16)));
        Long toolId = Long.valueOf(ids.get("toolId"));

        loginAs(888_888L, "userB", deptB, PermissionCode.TOOL_UPLOAD);

        BusinessException onDetail = assertThrows(BusinessException.class,
                () -> toolService.detail(toolId));
        assertEquals(Code.FORBIDDEN, onDetail.getCode(), "跨部门详情应 403");

        VersionSaveDTO v = new VersionSaveDTO();
        v.setVersion("v9.9");
        v.setFileKey(putFile(16));
        v.setFileName("x.py");
        v.setFileExt("py");
        v.setFileSize(16L);
        BusinessException onAdd = assertThrows(BusinessException.class,
                () -> toolWriteService.addVersion(toolId, v));
        assertEquals(Code.FORBIDDEN, onAdd.getCode(), "跨部门追加版本应 403");
    }

    /** 全局管理员不受部门隔离：可查看他部门工具 */
    @Test
    void globalAdminBypassesDeptIsolation() {
        Map<String, String> ids = toolWriteService.create(saveDto("私密工具", "v1.0", Scope.DEPT, putFile(16)));
        Long toolId = Long.valueOf(ids.get("toolId"));

        // 切到他部门、但持有 sys:setting（视为全局管理员）
        loginAs(888_888L, "adminB", deptB, PermissionCode.SYS_SETTING);

        ToolDetailVO detail = toolService.detail(toolId);
        assertEquals("私密工具", detail.getName(), "全局管理员应可跨部门查看");
    }

    /** 列表：默认只返回"本部门 + 公共"，不含他部门 DEPT 工具 */
    @Test
    void listOnlyShowsVisibleTools() {
        toolWriteService.create(saveDto("本部门工具", "v1.0", Scope.DEPT, putFile(16)));
        toolWriteService.create(saveDto("公共工具", "v1.0", Scope.PUBLIC, putFile(16)));

        // 另一个部门建一个 DEPT 工具
        loginAs(888_888L, "userB", deptB, PermissionCode.TOOL_UPLOAD);
        toolWriteService.create(saveDto("他部门工具", "v1.0", Scope.DEPT, putFile(16)));

        // 回到部门 A 查询
        loginAs(ADMIN_ID, "admin", deptA, PermissionCode.TOOL_UPLOAD);
        PageResult<ToolVO> page = toolQueryService.page(1, 20, null, null, null, null, null, null);

        List<String> names = page.getList().stream().map(ToolVO::getName).toList();
        assertTrue(names.contains("本部门工具"), "应含本部门工具");
        assertTrue(names.contains("公共工具"), "应含公共工具");
        assertFalse(names.contains("他部门工具"), "不应含他部门 DEPT 工具（数据隔离）");
    }

    /** 列表：scope 筛选只能收窄，不能扩大（传 DEPT 时仍只在本部门范围） */
    @Test
    void scopeFilterNarrowsOnlyWithinVisibility() {
        toolWriteService.create(saveDto("本部门工具", "v1.0", Scope.DEPT, putFile(16)));
        toolWriteService.create(saveDto("公共工具", "v1.0", Scope.PUBLIC, putFile(16)));

        loginAs(888_888L, "userB", deptB, PermissionCode.TOOL_UPLOAD);
        // 他部门用户按 PUBLIC 筛选：只能看到公共工具
        PageResult<ToolVO> publics = toolQueryService.page(1, 20, null, null, null, Scope.PUBLIC, null, null);
        assertEquals(1, publics.getTotal(), "跨部门按 PUBLIC 筛选只应命中公共工具");

        // 按 DEPT 筛选：他部门没有自己的工具 → 0 条（不能借此看到 A 部门 DEPT 工具）
        PageResult<ToolVO> depts = toolQueryService.page(1, 20, null, null, null, Scope.DEPT, null, null);
        assertEquals(0, depts.getTotal(), "scope 筛选不得扩大可见范围");
    }

    /** 列表：关键词匹配名称与简介 */
    @Test
    void listKeywordMatchesNameAndIntro() {
        ToolSaveDTO a = saveDto("Alpha工具", "v1.0", Scope.PUBLIC, putFile(16));
        a.setIntro("用于数据处理");
        toolWriteService.create(a);

        PageResult<ToolVO> byName = toolQueryService.page(1, 20, "Alpha", null, null, null, null, null);
        assertEquals(1, byName.getTotal(), "应按名称命中");

        PageResult<ToolVO> byIntro = toolQueryService.page(1, 20, "数据处理", null, null, null, null, null);
        assertEquals(1, byIntro.getTotal(), "应按简介命中");
    }

    /** 列表：按文件格式筛选（大小写不敏感输入） */
    @Test
    void listFilterByFileExt() {
        ToolSaveDTO py = saveDto("工具py", "v1.0", Scope.PUBLIC, putFile(16));
        toolWriteService.create(py);
        ToolSaveDTO zip = saveDto("工具zip", "v1.0", Scope.PUBLIC, putFile(16));
        zip.setFileExt("ZIP");
        toolWriteService.create(zip);

        PageResult<ToolVO> page = toolQueryService.page(1, 20, null, null, "zip", null, null, null);
        assertEquals(1, page.getTotal(), "应按格式命中，且输入大小写不敏感");
        assertEquals("zip", page.getList().get(0).getFileExt(), "返回的格式应为小写");
    }

    /** 列表：按标签筛选（多选命中任一） */
    @Test
    void listFilterByTags() {
        TagSaveDTO t1 = new TagSaveDTO();
        t1.setName("数据处理");
        String tagA = tagService.create(t1);
        TagSaveDTO t2 = new TagSaveDTO();
        t2.setName("网络工具");
        String tagB = tagService.create(t2);

        ToolSaveDTO withTag = saveDto("带标签工具", "v1.0", Scope.PUBLIC, putFile(16));
        withTag.setTagIds(List.of(tagA));
        toolWriteService.create(withTag);
        toolWriteService.create(saveDto("无标签工具", "v1.0", Scope.PUBLIC, putFile(16)));

        PageResult<ToolVO> page = toolQueryService.page(1, 20, null, tagA, null, null, null, null);
        assertEquals(1, page.getTotal(), "应按标签命中");

        // 命中任一：两个标签都传，仍只命中带标签的工具
        PageResult<ToolVO> any = toolQueryService.page(1, 20, null, tagA + "," + tagB, null, null, null, null);
        assertEquals(1, any.getTotal(), "多标签为'命中任一'");

        // 不存在的标签 → 空结果（不报错）
        PageResult<ToolVO> none = toolQueryService.page(1, 20, null, "999999", null, null, null, null);
        assertEquals(0, none.getTotal());
    }

    /** 列表：排序（下载次数升序 / 名称） */
    @Test
    void listSorting() {
        toolWriteService.create(saveDto("B工具", "v1.0", Scope.PUBLIC, putFile(16)));
        toolWriteService.create(saveDto("A工具", "v1.0", Scope.PUBLIC, putFile(16)));

        PageResult<ToolVO> byName = toolQueryService.page(1, 20, null, null, null, null, "name", "asc");
        assertEquals("A工具", byName.getList().get(0).getName(), "按名称升序");

        PageResult<ToolVO> byNameDesc = toolQueryService.page(1, 20, null, null, null, null, "name", "desc");
        assertEquals("B工具", byNameDesc.getList().get(0).getName(), "按名称降序");
    }

    /** 列表：分页与上限截断（pageSize > 100 收敛为 100） */
    @Test
    void listPaginationAndSizeCap() {
        for (int i = 1; i <= 3; i++) {
            toolWriteService.create(saveDto("工具" + i, "v1.0", Scope.PUBLIC, putFile(16)));
        }
        PageResult<ToolVO> p1 = toolQueryService.page(1, 2, null, null, null, null, null, null);
        assertEquals(3, p1.getTotal(), "总数应为 3");
        assertEquals(2, p1.getList().size(), "每页 2 条");

        PageResult<ToolVO> p2 = toolQueryService.page(2, 2, null, null, null, null, null, null);
        assertEquals(1, p2.getList().size(), "第 2 页应有 1 条");

        // pageSize 超过上限：不报错，返回结果
        PageResult<ToolVO> big = toolQueryService.page(1, 500, null, null, null, null, null, null);
        assertEquals(3, big.getList().size());
    }

    /** 列表不含已删除工具（契约 §9.2） */
    @Test
    void listExcludesDeletedTools() {
        Map<String, String> ids = toolWriteService.create(saveDto("待删工具", "v1.0", Scope.PUBLIC, putFile(16)));
        Long toolId = Long.valueOf(ids.get("toolId"));

        // 直接标记软删除（B4 才提供删除接口，这里验证列表口径）
        com.example.toolbox.tool.entity.ToolDO tool = toolMapper.selectById(toolId);
        tool.setDeleted(1);
        toolMapper.updateById(tool);

        PageResult<ToolVO> page = toolQueryService.page(1, 20, null, null, null, null, null, null);
        assertEquals(0, page.getTotal(), "列表不应含已删除工具");
    }

    /** 已删除工具：详情 → 2004（不是 1004 / 403） */
    @Test
    void deletedToolDetailReturns2004() {
        Map<String, String> ids = toolWriteService.create(saveDto("待删工具", "v1.0", Scope.PUBLIC, putFile(16)));
        Long toolId = Long.valueOf(ids.get("toolId"));
        com.example.toolbox.tool.entity.ToolDO tool = toolMapper.selectById(toolId);
        tool.setDeleted(1);
        toolMapper.updateById(tool);

        BusinessException ex = assertThrows(BusinessException.class, () -> toolService.detail(toolId));
        assertEquals(Code.TOOL_DELETED, ex.getCode(), "已删除工具应返回 2004");
    }

    /** 对外 VO 不暴露 fileKey（R1 + 契约 §9.3） */
    @Test
    void voDoesNotLeakFileKey() {
        Map<String, String> ids = toolWriteService.create(saveDto("工具A", "v1.0", Scope.DEPT, putFile(16)));
        ToolDetailVO detail = toolService.detail(Long.valueOf(ids.get("toolId")));

        List<String> voFields = java.util.Arrays.stream(ToolDetailVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList();
        assertFalse(voFields.contains("fileKey"), "详情 VO 不得含 fileKey");

        List<String> verFields = java.util.Arrays.stream(
                        com.example.toolbox.tool.vo.ToolVersionVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList();
        assertFalse(verFields.contains("fileKey"), "版本 VO 不得含 fileKey（契约 §9.3）");
    }

    /** 不存在的工具 → 1004 */
    @Test
    void missingToolReturns404Code() {
        BusinessException ex = assertThrows(BusinessException.class, () -> toolService.detail(999999L));
        assertEquals(Code.NOT_FOUND, ex.getCode());
    }

    // ---------------- 标签（T8） ----------------

    /** 标签：增改删 + 重名 1005 + 关联工具数 */
    @Test
    void tagCrudAndToolCount() {
        TagSaveDTO dto = new TagSaveDTO();
        dto.setName("数据处理");
        String id = tagService.create(dto);
        assertEquals(1, tagService.list(null).size());

        // 重名 → 1005
        BusinessException dup = assertThrows(BusinessException.class, () -> tagService.create(dto));
        assertEquals(Code.CONFLICT, dup.getCode());

        // 关联一个工具后，toolCount 应更新
        ToolSaveDTO tool = saveDto("工具A", "v1.0", Scope.PUBLIC, putFile(16));
        tool.setTagIds(List.of(id));
        toolWriteService.create(tool);
        assertEquals(1L, tagService.list(null).get(0).getToolCount(), "标签应统计关联工具数");

        // 编辑
        TagSaveDTO renamed = new TagSaveDTO();
        renamed.setName("数据处理2");
        tagService.update(Long.valueOf(id), renamed);
        assertEquals("数据处理2", tagService.list(null).get(0).getName());
    }

    /** 删除标签：只解除关联，**不删除工具**（契约 §七） */
    @Test
    void deletingTagKeepsTools() {
        TagSaveDTO t = new TagSaveDTO();
        t.setName("临时标签");
        String tagId = tagService.create(t);

        ToolSaveDTO tool = saveDto("工具A", "v1.0", Scope.PUBLIC, putFile(16));
        tool.setTagIds(List.of(tagId));
        Map<String, String> ids = toolWriteService.create(tool);

        tagService.delete(Long.valueOf(tagId));

        assertEquals(0, tagService.list(null).size(), "标签应已删除");
        ToolDetailVO detail = toolService.detail(Long.valueOf(ids.get("toolId")));
        assertNotNull(detail, "工具必须仍然存在");
        assertTrue(detail.getTags().isEmpty(), "工具的标签关联应被解除");
    }

    /** 标签不存在 → 1004 */
    @Test
    void tagNotFound() {
        TagSaveDTO dto = new TagSaveDTO();
        dto.setName("x");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> tagService.update(999999L, dto));
        assertEquals(Code.NOT_FOUND, ex.getCode());
    }

    /** 建工具时引用不存在的标签 → 1004 */
    @Test
    void createToolWithMissingTagRejected() {
        ToolSaveDTO tool = saveDto("工具A", "v1.0", Scope.PUBLIC, putFile(16));
        tool.setTagIds(List.of("999999"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolWriteService.create(tool));
        assertEquals(Code.NOT_FOUND, ex.getCode());
    }
}
