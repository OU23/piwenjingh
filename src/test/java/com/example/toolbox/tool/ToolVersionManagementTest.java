package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.dto.DeptSaveDTO;
import com.example.toolbox.dept.service.DeptService;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.dto.ToolBatchDTO;
import com.example.toolbox.tool.dto.ToolNameCheckDTO;
import com.example.toolbox.tool.dto.ToolSaveDTO;
import com.example.toolbox.tool.dto.VersionSaveDTO;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.service.ToolPurgeService;
import com.example.toolbox.tool.service.ToolService;
import com.example.toolbox.tool.service.ToolTrashService;
import com.example.toolbox.tool.service.ToolVersionDeleteService;
import com.example.toolbox.tool.service.ToolWriteService;
import com.example.toolbox.tool.vo.ToolDetailVO;
import com.example.toolbox.tool.vo.ToolNameCheckVO;
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
 * 版本管理（M-24 / 契约 **v1.22**）：名称按上传人、版本**物理删除**、latest 回退。
 *
 * <p>v1.22 调整（老大 2026-10-19）：历史版本删除**不进回收站**，直接删文件 + 删记录；
 * 只有「删除整个工具」才进回收站。因此本类不再覆盖"版本软删/恢复/版本页签"。
 */
class ToolVersionManagementTest extends IntegrationTestBase {

    @Autowired
    private ToolService toolService;
    @Autowired
    private ToolWriteService toolWriteService;
    @Autowired
    private ToolVersionDeleteService toolVersionDeleteService;
    @Autowired
    private ToolTrashService toolTrashService;
    @Autowired
    private ToolPurgeService toolPurgeService;
    @Autowired
    private StorageService storageService;
    @Autowired
    private DeptService deptService;

    private static final long UPLOADER = 900_001L;
    private static final long OTHER = 900_002L;

    private Long deptA;
    private Long deptB;

    @BeforeEach
    void setUpData() {
        deptA = createDept("研发部");
        deptB = createDept("市场部");
        loginAs(UPLOADER, "uploader", deptA, PermissionCode.TOOL_UPLOAD);
    }

    // ---------------- 名称唯一性（按上传人） ----------------

    /** A3：同上传人同名（未删）→ existed=true / canAppend=true */
    @Test
    void checkNameHitsSameUploader() {
        Map<String, String> ids = create("工具A", "v1.0");

        ToolNameCheckVO vo = checkName("工具A");
        assertTrue(vo.getExisted());
        assertEquals(ids.get("toolId"), vo.getToolId());
        assertTrue(vo.getCanAppend(), "同人同名 → 可追加版本");
        assertFalse(vo.getInTrash());
    }

    /** A5：回收站里同人同名 → existed=false、inTrash=true、trashToolId 可用 */
    @Test
    void checkNameFlagsTrashedSameName() {
        Map<String, String> ids = create("工具A", "v1.0");
        Long toolId = Long.valueOf(ids.get("toolId"));
        // 整工具删除（进回收站）
        toolMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<ToolDO>lambdaUpdate()
                .eq(ToolDO::getId, toolId)
                .set(ToolDO::getDeleted, ToolService.DELETED));

        ToolNameCheckVO vo = checkName("工具A");
        assertFalse(vo.getExisted(), "已删工具不算命中，否则与 inTrash 分支冲突");
        assertFalse(vo.getCanAppend());
        assertTrue(vo.getInTrash());
        assertEquals(ids.get("toolId"), vo.getTrashToolId());
    }

    /** A5：不同上传人可同名并存（GitHub 模式） */
    @Test
    void sameNameDifferentUploadersCoexist() {
        String a = create("同名工具", "v1.0").get("toolId");

        loginAs(OTHER, "other", deptB, PermissionCode.TOOL_UPLOAD);
        String b = create("同名工具", "v1.0").get("toolId");

        assertNotNull(a);
        assertNotNull(b);
        assertFalse(a.equals(b), "不同上传人应得到两个独立工具");
    }

    // ---------------- 版本物理删除 ----------------

    /** v1.22 核心：删版本 = 删记录 + 删文件，不可恢复 */
    @Test
    void deleteVersionRemovesRecordAndFile() {
        Map<String, String> ids = create("工具A", "v1.0");
        Long toolId = Long.valueOf(ids.get("toolId"));
        Long v1 = Long.valueOf(ids.get("versionId"));
        toolWriteService.addVersion(toolId, versionDto("v2.0", putFile(16)));

        String fileKey = toolVersionMapper.selectById(v1).getFileKey();
        assertTrue(storageService.exists(fileKey), "前置：文件存在");

        toolVersionDeleteService.delete(toolId, v1);

        assertNull(toolVersionMapper.selectById(v1), "版本记录应被物理删除");
        assertFalse(storageService.exists(fileKey), "本地落点文件应被物理删除");
        assertEquals(1, toolService.detail(toolId).getVersions().size());
    }

    /** 不允许删最后一个版本 → 1005（引导去删整个工具） */
    @Test
    void cannotDeleteLastVersion() {
        Map<String, String> ids = create("工具A", "v1.0");
        Long toolId = Long.valueOf(ids.get("toolId"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolVersionDeleteService.delete(toolId, Long.valueOf(ids.get("versionId"))));
        assertEquals(Code.CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains("整个工具"));
        assertEquals(1, toolService.detail(toolId).getVersions().size(), "失败时不得删掉任何版本");
    }

    /** 删最新版 → latest 回退到上一版 */
    @Test
    void deleteLatestFallsBackToPrevious() {
        Map<String, String> ids = create("工具A", "v1.0");
        Long toolId = Long.valueOf(ids.get("toolId"));
        String v2 = toolWriteService.addVersion(toolId, versionDto("v2.0", putFile(16)));

        assertEquals("v2.0", toolService.detail(toolId).getLatestVersion());

        toolVersionDeleteService.delete(toolId, Long.valueOf(v2));

        ToolDetailVO detail = toolService.detail(toolId);
        assertEquals("v1.0", detail.getLatestVersion(), "删最新版应回退到上一版");
        assertEquals(1, detail.getVersions().size());
        assertTrue(detail.getVersions().get(0).getIsLatest());
    }

    /** 删非最新版 → latest 不变 */
    @Test
    void deleteOldVersionKeepsLatest() {
        Map<String, String> ids = create("工具A", "v1.0");
        Long toolId = Long.valueOf(ids.get("toolId"));
        Long v1 = Long.valueOf(ids.get("versionId"));
        String v2 = toolWriteService.addVersion(toolId, versionDto("v2.0", putFile(16)));

        toolVersionDeleteService.delete(toolId, v1);

        assertEquals(Long.valueOf(v2), toolMapper.selectById(toolId).getLatestVersionId());
        assertEquals(1, toolService.detail(toolId).getVersions().size());
    }

    /** 物理删除后可重新上传同一版本号（DB 唯一键已释放） */
    @Test
    void versionNumberReusableAfterPhysicalDelete() {
        Map<String, String> ids = create("工具A", "v1.0");
        Long toolId = Long.valueOf(ids.get("toolId"));
        Long v1 = Long.valueOf(ids.get("versionId"));
        toolWriteService.addVersion(toolId, versionDto("v2.0", putFile(16)));

        toolVersionDeleteService.delete(toolId, v1);

        // 旧记录已不存在 → 再传 v1.0 不应冲突
        String reused = toolWriteService.addVersion(toolId, versionDto("v1.0", putFile(16)));
        assertNotNull(reused);
        assertEquals(2, toolService.detail(toolId).getVersions().size());
    }

    /** 版本不属于该工具 / 不存在 → 1004；工具已删 → 2004 */
    @Test
    void deleteVersionRejectsInvalidTargets() {
        Map<String, String> a = create("工具A", "v1.0");
        Long toolA = Long.valueOf(a.get("toolId"));
        Long versionA = Long.valueOf(a.get("versionId"));
        toolWriteService.addVersion(toolA, versionDto("v2.0", putFile(16)));
        Long toolB = Long.valueOf(create("工具B", "v1.0").get("toolId"));

        BusinessException foreign = assertThrows(BusinessException.class,
                () -> toolVersionDeleteService.delete(toolB, versionA));
        assertEquals(Code.NOT_FOUND, foreign.getCode());

        BusinessException missing = assertThrows(BusinessException.class,
                () -> toolVersionDeleteService.delete(toolA, 9_999_999L));
        assertEquals(Code.NOT_FOUND, missing.getCode());
    }

    /** 彻底删除工具：版本记录全部物理清除 */
    @Test
    void purgeToolRemovesAllVersions() {
        Map<String, String> ids = create("工具A", "v1.0");
        Long toolId = Long.valueOf(ids.get("toolId"));
        toolWriteService.addVersion(toolId, versionDto("v2.0", putFile(16)));

        // 先软删工具（进回收站），再彻底删除
        toolMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<ToolDO>lambdaUpdate()
                .eq(ToolDO::getId, toolId)
                .set(ToolDO::getDeleted, ToolService.DELETED));

        ToolBatchDTO dto = new ToolBatchDTO();
        dto.setIds(List.of(String.valueOf(toolId)));
        toolPurgeService.purge(dto);

        assertEquals(0L, toolVersionMapper.selectCount(null), "工具的版本应被物理清除");
        assertNull(toolMapper.selectById(toolId));
    }

    /** 删除工具（进回收站）不动版本记录；恢复后版本仍在 */
    @Test
    void softDeleteAndRestoreToolKeepsVersions() {
        Map<String, String> ids = create("工具A", "v1.0");
        Long toolId = Long.valueOf(ids.get("toolId"));
        toolWriteService.addVersion(toolId, versionDto("v2.0", putFile(16)));

        Long toolVersionCount = toolVersionMapper.selectCount(null);
        toolTrashService.softDelete(toolId);
        assertEquals(ToolService.DELETED, toolMapper.selectById(toolId).getDeleted());
        assertEquals(toolVersionCount, toolVersionMapper.selectCount(null), "软删工具不得删版本");

        toolTrashService.restore(toolId);
        assertEquals(ToolService.NOT_DELETED, toolMapper.selectById(toolId).getDeleted());
        assertEquals(2, toolService.detail(toolId).getVersions().size());
    }

    // ---------------- 夹具 ----------------

    private Long createDept(String name) {
        loginAs(UPLOADER, "uploader", null, PermissionCode.DEPT_MANAGE);
        DeptSaveDTO d = new DeptSaveDTO();
        d.setName(name);
        return Long.valueOf(deptService.create(d));
    }

    private String putFile(long size) {
        String key = storageService.generateKey();
        try {
            storageService.save(key, new ByteArrayInputStream(new byte[(int) size]), size);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return key;
    }

    private Map<String, String> create(String name, String version) {
        ToolSaveDTO dto = new ToolSaveDTO();
        dto.setName(name);
        dto.setVersion(version);
        dto.setIntro("简介");
        dto.setReadme("说明");
        dto.setFileExt("py");
        dto.setPlatform("Windows");
        dto.setDependency("无");
        dto.setScope(Scope.DEPT);
        dto.setFileKey(putFile(16));
        dto.setFileName("demo.py");
        dto.setFileSize(16L);
        return toolWriteService.create(dto);
    }

    private VersionSaveDTO versionDto(String version, String fileKey) {
        VersionSaveDTO dto = new VersionSaveDTO();
        dto.setVersion(version);
        dto.setFileKey(fileKey);
        dto.setFileName("demo.py");
        dto.setFileExt("py");
        dto.setFileSize(16L);
        return dto;
    }

    private ToolNameCheckVO checkName(String name) {
        ToolNameCheckDTO dto = new ToolNameCheckDTO();
        dto.setName(name);
        return toolService.checkName(dto);
    }
}
