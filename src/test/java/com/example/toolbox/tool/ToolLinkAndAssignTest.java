package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.constant.ToolType;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.dto.ToolDeptDTO;
import com.example.toolbox.tool.dto.ToolLinkSaveDTO;
import com.example.toolbox.tool.dto.ToolSaveDTO;
import com.example.toolbox.tool.dto.ToolUpdateDTO;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.service.DownloadService;
import com.example.toolbox.tool.service.ToolQueryService;
import com.example.toolbox.tool.service.ToolService;
import com.example.toolbox.tool.service.ToolTrashService;
import com.example.toolbox.tool.service.ToolWriteService;
import com.example.toolbox.tool.vo.ToolDetailVO;
import com.example.toolbox.tool.vo.ToolVO;
import com.example.toolbox.user.entity.UserDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-25（契约 v1.23）：工具归属调整 + 链接工具。
 *
 * <p>覆盖方案 §十 自测清单的后端部分：
 * <ul>
 *   <li>链接工具创建（无版本记录）与字段约定、URL 协议白名单、下载 2004；</li>
 *   <li>编辑链接工具不再 400（忽略 {@code version} / {@code fileExt}）；FILE 传 {@code linkUrl} → 1003；</li>
 *   <li>创建时指定归属部门（仅 {@code tool:assign} 生效，无权限则忽略）；</li>
 *   <li>调整归属部门（权限点、已删除 2004、幂等）、调整后上传人仍可见可管；</li>
 *   <li>列表 {@code toolType} 筛选、{@code tool:assign} 跨部门可见。</li>
 * </ul>
 */
class ToolLinkAndAssignTest extends IntegrationTestBase {

    @Autowired
    private ToolWriteService toolWriteService;
    @Autowired
    private ToolService toolService;
    @Autowired
    private ToolQueryService toolQueryService;
    @Autowired
    private ToolTrashService toolTrashService;
    @Autowired
    private DownloadService downloadService;
    @Autowired
    private StorageService storageService;

    private Long deptA;
    private Long deptB;
    private UserDO uploader;      // deptA，仅 tool:upload / tool:download
    private UserDO assigner;      // deptA，额外持 tool:assign
    private UserDO deptBMember;   // deptB，普通成员
    private UserDO deptAMember;   // deptA，普通成员（既非上传人）

    @BeforeEach
    void setUpData() {
        deptA = createDept("研发部");
        deptB = createDept("财务部");
        uploader = insertUser("uploader", deptA, PermissionCode.TOOL_UPLOAD, PermissionCode.TOOL_DOWNLOAD);
        assigner = insertUser("assigner", deptA, PermissionCode.TOOL_ASSIGN, PermissionCode.TOOL_DOWNLOAD);
        deptBMember = insertUser("fin01", deptB, PermissionCode.TOOL_DOWNLOAD);
        deptAMember = insertUser("dev02", deptA, PermissionCode.TOOL_DOWNLOAD);
    }

    // ---------------- 需求 6：链接工具 ----------------

    /** 链接工具：不写版本记录，字段按契约 §2.6 约定落库；详情 versions 为 [] */
    @Test
    void createLinkToolPersistsWithoutVersion() {
        loginAs(uploader);
        Long toolId = Long.valueOf(toolWriteService.createLink(linkDto("财务对账入口", "https://fin.example.com/app"))
                .get("toolId"));

        ToolDO raw = toolMapper.selectById(toolId);
        assertEquals(ToolType.LINK, raw.getToolType());
        assertEquals("https://fin.example.com/app", raw.getLinkUrl());
        assertEquals(0L, raw.getLatestVersionId(), "LINK 无版本：latest_version_id 应为 0");
        assertEquals("", raw.getLatestVersion(), "LINK 无版本：latest_version 应为空串");
        assertEquals(0L, raw.getFileSize(), "LINK 无文件：file_size 应为 0");
        assertEquals("", raw.getFileExt(), "LINK 无文件：file_ext 应为空串");
        assertEquals(deptA, raw.getDeptId(), "未指定 deptId 时归属自己部门");

        assertEquals(0L, toolVersionMapper.selectCount(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<ToolVersionDO>lambdaQuery()
                                .eq(ToolVersionDO::getToolId, toolId)),
                "链接工具不得产生 tb_tool_version 记录");

        ToolDetailVO detail = toolService.detail(toolId);
        assertEquals(ToolType.LINK, detail.getToolType());
        assertEquals("https://fin.example.com/app", detail.getLinkUrl());
        assertTrue(detail.getVersions().isEmpty(), "LINK 详情 versions 应为空列表");
    }

    /** URL 协议白名单：javascript: / 非 http(s) → 1003 */
    @Test
    void linkUrlProtocolWhitelistRejectsNonHttp() {
        loginAs(uploader);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolWriteService.createLink(linkDto("坏链接", "javascript:alert(1)")));
        assertEquals(Code.PARAM_INVALID, ex.getCode(), "非 http/https 应 1003");

        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> toolWriteService.createLink(linkDto("坏链接2", "ftp://example.com/a")));
        assertEquals(Code.PARAM_INVALID, ex2.getCode());
    }

    /** 链接工具调下载链路 → 2004（无文件可下） */
    @Test
    void linkToolDownloadReturns2004() {
        loginAs(uploader);
        Long toolId = Long.valueOf(toolWriteService.createLink(linkDto("入口", "https://a.example.com"))
                .get("toolId"));

        LoginUser user = UserContext.get();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadService.plan(toolId, null, user, true));
        assertEquals(Code.TOOL_DELETED, ex.getCode(), "链接工具下载应 2004");
    }

    /** 编辑链接工具：不再 400；忽略 version / fileExt；linkUrl 覆盖生效 */
    @Test
    void updateLinkToolIgnoresVersionAndExtAndReplacesUrl() {
        loginAs(uploader);
        Long toolId = Long.valueOf(toolWriteService.createLink(linkDto("入口", "https://old.example.com"))
                .get("toolId"));

        ToolUpdateDTO dto = new ToolUpdateDTO();
        dto.setName("入口");
        dto.setIntro("新简介");
        dto.setScope(Scope.DEPT);
        dto.setVersion("v9.9");     // v1.23：忽略
        dto.setFileExt("zip");      // v1.23：忽略
        dto.setLinkUrl("https://new.example.com/x");
        toolWriteService.update(toolId, dto);

        ToolDO raw = toolMapper.selectById(toolId);
        assertEquals("https://new.example.com/x", raw.getLinkUrl(), "linkUrl 应被覆盖");
        assertEquals("", raw.getLatestVersion(), "version 应被忽略（latest_version 不变）");
        assertEquals("", raw.getFileExt(), "fileExt 应被忽略（LINK 恒为空）");
        assertEquals("新简介", raw.getIntro());
    }

    /** FILE 类型传 linkUrl → 1003 */
    @Test
    void fileToolRejectsLinkUrlOnUpdate() {
        loginAs(uploader);
        Long toolId = createFileTool("文件工具");

        ToolUpdateDTO dto = new ToolUpdateDTO();
        dto.setName("文件工具");
        dto.setIntro("简介");
        dto.setScope(Scope.DEPT);
        dto.setLinkUrl("https://a.example.com");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolWriteService.update(toolId, dto));
        assertEquals(Code.PARAM_INVALID, ex.getCode(), "文件工具不支持链接地址");
    }

    /** 文件工具编辑：不传 version / fileExt 也应成功（去掉 @NotBlank） */
    @Test
    void updateFileToolWithoutVersionAndExtSucceeds() {
        loginAs(uploader);
        Long toolId = createFileTool("文件工具");

        ToolUpdateDTO dto = new ToolUpdateDTO();
        dto.setName("文件工具");
        dto.setIntro("改了简介");
        dto.setScope(Scope.PUBLIC);
        toolWriteService.update(toolId, dto);

        ToolDO raw = toolMapper.selectById(toolId);
        assertEquals("改了简介", raw.getIntro());
        assertEquals("v1.0", raw.getLatestVersion(), "未传 version 时 latest_version 不应变化");
    }

    // ---------------- 需求 4：创建时指定归属部门 ----------------

    /** 持 tool:assign + 传 deptId → 归属该部门 */
    @Test
    void createWithDeptIdAppliesWhenHasAssignPermission() {
        loginAs(assigner);
        ToolSaveDTO dto = fileDto("研发做给财务的工具", deptB);
        Long toolId = Long.valueOf(toolWriteService.create(dto).get("toolId"));

        assertEquals(deptB, toolMapper.selectById(toolId).getDeptId(), "持 tool:assign 时应使用指定部门");
    }

    /** 不持 tool:assign + 传 deptId → 忽略（不报错），归属自己部门 */
    @Test
    void createWithDeptIdIgnoredWithoutAssignPermission() {
        loginAs(uploader);
        ToolSaveDTO dto = fileDto("普通上传", deptB);
        Long toolId = Long.valueOf(toolWriteService.create(dto).get("toolId"));

        assertEquals(deptA, toolMapper.selectById(toolId).getDeptId(), "无 tool:assign 时应忽略 deptId");
    }

    /** deptId 不存在 → 1004 */
    @Test
    void createWithUnknownDeptReturns1004() {
        loginAs(assigner);
        ToolSaveDTO dto = fileDto("不存在部门", 999_999L);
        BusinessException ex = assertThrows(BusinessException.class, () -> toolWriteService.create(dto));
        assertEquals(Code.NOT_FOUND, ex.getCode());
    }

    // ---------------- 需求 1 / 2 / 3：归属调整与上传人恒可见 ----------------

    /** 改部门后：B 部门成员可见，原部门其他人不可见，上传人仍可见可编辑 */
    @Test
    void changeDeptMovesVisibilityAndKeepsUploaderAccess() {
        loginAs(uploader);
        Long toolId = createFileTool("跨部门工具");

        // 归属调整（tool:assign）
        loginAs(assigner);
        toolWriteService.changeDept(toolId, deptDto(deptB));
        assertEquals(deptB, toolMapper.selectById(toolId).getDeptId());

        // 归属部门成员可见
        loginAs(deptBMember);
        assertEquals("跨部门工具", toolService.detail(toolId).getName(), "归属部门成员应可见");

        // 原部门其他人不可见
        loginAs(deptAMember);
        assertEquals(Code.FORBIDDEN, assertThrows(BusinessException.class,
                () -> toolService.detail(toolId)).getCode(), "原部门其他成员应 403");

        // 上传人仍可见 + 可编辑（§2.2 第 5 条）
        loginAs(uploader);
        assertEquals("跨部门工具", toolService.detail(toolId).getName(), "上传人恒可见");
        ToolUpdateDTO dto = new ToolUpdateDTO();
        dto.setName("跨部门工具");
        dto.setIntro("上传人改的简介");
        dto.setScope(Scope.DEPT);
        toolWriteService.update(toolId, dto);
        assertEquals("上传人改的简介", toolMapper.selectById(toolId).getIntro(), "上传人仍可编辑");
    }

    /** 归属部门成员不能编辑（只读）→ 403 */
    @Test
    void deptMemberCannotEditAfterAssign() {
        loginAs(uploader);
        Long toolId = createFileTool("只读工具");
        loginAs(assigner);
        toolWriteService.changeDept(toolId, deptDto(deptB));

        loginAs(deptBMember);
        ToolUpdateDTO dto = new ToolUpdateDTO();
        dto.setName("只读工具");
        dto.setIntro("想改");
        dto.setScope(Scope.DEPT);
        assertEquals(Code.FORBIDDEN, assertThrows(BusinessException.class,
                () -> toolWriteService.update(toolId, dto)).getCode(), "归属部门成员只能看 + 用");
    }

    /** 全局管理员但**未**授予 tool:assign → 仍 403（方案 §6.2 实现要点 1） */
    @Test
    void changeDeptRequiresAssignPermissionEvenForGlobalAdmin() {
        loginAs(uploader);
        Long toolId = createFileTool("工具");
        loginAs(999_001L, "sysadmin", deptA, PermissionCode.SYS_SETTING);

        assertEquals(Code.FORBIDDEN, assertThrows(BusinessException.class,
                () -> toolWriteService.changeDept(toolId, deptDto(deptB))).getCode());
    }

    /** 已删除（回收站内）→ 2004；不存在 → 1004；同部门 → 幂等成功 */
    @Test
    void changeDeptEdgeCases() {
        loginAs(uploader);
        Long toolId = createFileTool("工具");

        loginAs(assigner);
        // 幂等：与当前部门相同，直接成功
        toolWriteService.changeDept(toolId, deptDto(deptA));
        assertEquals(deptA, toolMapper.selectById(toolId).getDeptId());

        // 不存在 → 1004
        assertEquals(Code.NOT_FOUND, assertThrows(BusinessException.class,
                () -> toolWriteService.changeDept(999_999L, deptDto(deptB))).getCode());

        // 已删除 → 2004
        loginAs(uploader);
        toolTrashService.softDelete(toolId);
        loginAs(assigner);
        assertEquals(Code.TOOL_DELETED, assertThrows(BusinessException.class,
                () -> toolWriteService.changeDept(toolId, deptDto(deptB))).getCode(),
                "回收站里的工具不允许改部门");
    }

    /** 部门已停用 → 1003 */
    @Test
    void changeDeptToDisabledDeptReturns1003() {
        loginAs(uploader);
        Long toolId = createFileTool("工具");
        deptB = createDept("已停用部门");
        DeptDO dept = deptMapper.selectById(deptB);
        dept.setStatus(StatusValue.DISABLED);
        deptMapper.updateById(dept);

        loginAs(assigner);
        assertEquals(Code.PARAM_INVALID, assertThrows(BusinessException.class,
                () -> toolWriteService.changeDept(toolId, deptDto(deptB))).getCode());
    }

    // ---------------- 需求 6：类型筛选 + tool:assign 跨部门可见 ----------------

    /** toolType 筛选：ALL / FILE / LINK；非法值 → 1003 */
    @Test
    void listFiltersByToolType() {
        loginAs(uploader);
        createFileTool("文件工具");
        toolWriteService.createLink(linkDto("链接工具", "https://a.example.com"));

        loginAs(uploader);
        assertEquals(2, page(null).getTotal(), "ALL 应返回两类");
        List<ToolVO> files = page(ToolType.FILE).getList();
        assertEquals(1, files.size());
        assertEquals(ToolType.FILE, files.get(0).getToolType());

        List<ToolVO> links = page(ToolType.LINK).getList();
        assertEquals(1, links.size());
        assertEquals(ToolType.LINK, links.get(0).getToolType());
        assertNotNull(links.get(0).getLinkUrl(), "LINK 列表项应带 linkUrl");

        assertEquals(Code.PARAM_INVALID, assertThrows(BusinessException.class,
                () -> page("PAYLOAD")).getCode(), "非法 toolType 应 1003");
    }

    /** tool:assign 可跨部门看到全部工具（仅工具域） */
    @Test
    void assignPermissionSeesAllToolsAcrossDepts() {
        loginAs(uploader);
        createFileTool("研发工具");
        loginAs(assigner);
        createFileTool("另一个研发工具");

        loginAs(deptBMember);
        assertEquals(0, page(null).getTotal(), "普通跨部门成员看不到 DEPT 工具");

        loginAs(assigner);
        assertEquals(2, page(null).getTotal(), "持 tool:assign 应看到全部工具");
    }

    // ---------------- 夹具 ----------------

    private com.example.toolbox.common.result.PageResult<ToolVO> page(String toolType) {
        return toolQueryService.page(1, 20, null, null, null, null, toolType, null, null);
    }

    private Long createFileTool(String name) {
        return Long.valueOf(toolWriteService.create(fileDto(name, null)).get("toolId"));
    }

    private ToolSaveDTO fileDto(String name, Long deptId) {
        String key = storageService.generateKey();
        save(key, 8);
        ToolSaveDTO dto = new ToolSaveDTO();
        dto.setName(name);
        dto.setVersion("v1.0");
        dto.setIntro("简介");
        dto.setFileExt("zip");
        dto.setScope(Scope.DEPT);
        dto.setFileKey(key);
        dto.setFileName("a.zip");
        dto.setFileSize(8L);
        dto.setDeptId(deptId == null ? null : String.valueOf(deptId));
        return dto;
    }

    private ToolLinkSaveDTO linkDto(String name, String url) {
        ToolLinkSaveDTO dto = new ToolLinkSaveDTO();
        dto.setName(name);
        dto.setLinkUrl(url);
        dto.setIntro("简介");
        dto.setScope(Scope.DEPT);
        return dto;
    }

    private ToolDeptDTO deptDto(Long deptId) {
        ToolDeptDTO dto = new ToolDeptDTO();
        dto.setDeptId(String.valueOf(deptId));
        return dto;
    }

    private void save(String key, long size) {
        try {
            storageService.save(key, new ByteArrayInputStream(new byte[(int) size]), size);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
            com.example.toolbox.user.entity.UserPermissionDO up =
                    new com.example.toolbox.user.entity.UserPermissionDO();
            up.setUserId(u.getId());
            up.setPermissionCode(p);
            up.setCreateTime(LocalDateTime.now());
            userPermissionMapper.insert(up);
        }
        return u;
    }

    private void loginAs(UserDO u) {
        super.loginAs(u.getId(), u.getUsername(), u.getDeptId(), permissionCodesOf(u));
    }

    private String[] permissionCodesOf(UserDO u) {
        return userPermissionMapper.selectList(null).stream()
                .filter(p -> p.getUserId().equals(u.getId()))
                .map(com.example.toolbox.user.entity.UserPermissionDO::getPermissionCode)
                .toArray(String[]::new);
    }

    /** 供断言的空值防御：FILE 工具的 linkUrl 必须为 null（不泄露脏数据） */
    @Test
    void fileToolDetailHasNullLinkUrl() {
        loginAs(uploader);
        Long toolId = createFileTool("文件工具");
        ToolDetailVO detail = toolService.detail(toolId);
        assertEquals(ToolType.FILE, detail.getToolType());
        assertNull(detail.getLinkUrl(), "FILE 的 linkUrl 应为 null");
    }
}
