package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tag.entity.TagDO;
import com.example.toolbox.tool.dto.ToolBatchDTO;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolTagDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.service.ToolPurgeService;
import com.example.toolbox.tool.service.ToolService;
import com.example.toolbox.tool.service.ToolTrashService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 彻底删除与清空回收站（B4 验收 A4 的反面 / C5 幂等）。
 *
 * <p>本类钉住"**彻底删除必须真的释放磁盘**"这条 —— 只删库记录不删文件是常见的假实现，
 * 而契约与变更说明都明确要求删物理文件（回收站的根本目的）。
 * 同时验证"仅作用于已软删工具"（防误删线上工具）与幂等性。
 */
class ToolPurgeTest extends IntegrationTestBase {

    @Autowired
    private ToolPurgeService toolPurgeService;
    @Autowired
    private ToolTrashService toolTrashService;
    @Autowired
    private StorageService storageService;

    private Long deptId;
    private Long purgedToolId;
    private Long normalToolId;
    private String purgedFileKey;
    private String normalFileKey;

    @BeforeEach
    void setUpData() {
        DeptDO dept = new DeptDO();
        dept.setName("研发部");
        dept.setStatus(StatusValue.ENABLED);
        dept.setCreateTime(LocalDateTime.now());
        deptMapper.insert(dept);
        deptId = dept.getId();

        loginAs(999_999L, "admin", deptId, PermissionCode.TOOL_DELETE);

        purgedFileKey = saveFile();
        purgedToolId = createToolWithVersion("已删工具", purgedFileKey);
        normalFileKey = saveFile();
        normalToolId = createToolWithVersion("线上工具", normalFileKey);

        // 挂一个标签关联，验证彻底删除会一并清理关联
        TagDO tag = new TagDO();
        tag.setName("标签X");
        tag.setCreateTime(LocalDateTime.now());
        tagMapper.insert(tag);
        ToolTagDO rel = new ToolTagDO();
        rel.setToolId(purgedToolId);
        rel.setTagId(tag.getId());
        rel.setCreateTime(LocalDateTime.now());
        toolTagMapper.insert(rel);

        // 预置：把待彻底删除的工具先软删
        toolTrashService.softDelete(purgedToolId);
    }

    /** 彻底删除：工具 + 版本 + 标签关联 + **磁盘物理文件** 全部消失 */
    @Test
    void purgeRemovesRecordsAndPhysicalFile() {
        assertTrue(storageService.exists(purgedFileKey), "前置：物理文件应存在");
        assertEquals(1, toolTagMapper.selectCount(null).intValue(), "前置：应有 1 条标签关联");

        ToolBatchDTO dto = new ToolBatchDTO();
        dto.setIds(List.of(String.valueOf(purgedToolId)));
        toolPurgeService.purge(dto);

        assertNull(toolMapper.selectById(purgedToolId), "工具记录应被物理删除");
        assertEquals(0, versionCountOf(purgedToolId), "该工具的版本记录应一并删除");
        assertEquals(1, toolVersionMapper.selectCount(null).intValue(),
                "线上工具的版本不得被连带删除");
        assertEquals(0, toolTagMapper.selectCount(null).intValue(), "标签关联应一并删除");
        assertFalse(storageService.exists(purgedFileKey), "★ 磁盘物理文件必须被删除（否则等于没释放空间）");
        assertEquals(1, tagMapper.selectCount(null).intValue(), "标签本身不得被删除（只解除关联）");
    }

    /** 仅作用于已软删工具：未软删的 ID 被忽略（防误删线上工具） */
    @Test
    void purgeIgnoresToolThatIsNotSoftDeleted() {
        ToolBatchDTO dto = new ToolBatchDTO();
        dto.setIds(List.of(String.valueOf(normalToolId), String.valueOf(purgedToolId)));
        toolPurgeService.purge(dto);

        assertTrue(storageService.exists(normalFileKey), "线上工具的文件不得被删");
        assertEquals("线上工具", toolMapper.selectById(normalToolId).getName(), "线上工具记录不得被删");
        assertNull(toolMapper.selectById(purgedToolId), "已软删的应被彻底删除");
    }

    /** 幂等：对同一批重复彻底删除、或删除不存在的 ID，均成功且无异常 */
    @Test
    void purgeIsIdempotent() {
        ToolBatchDTO dto = new ToolBatchDTO();
        dto.setIds(List.of(String.valueOf(purgedToolId)));
        toolPurgeService.purge(dto);
        toolPurgeService.purge(dto);                       // 第二次：记录已不在

        ToolBatchDTO missing = new ToolBatchDTO();
        missing.setIds(List.of("999999"));
        toolPurgeService.purge(missing);                   // 不存在 → 忽略，不报错

        assertEquals(1, toolMapper.selectCount(null).intValue(), "只剩线上工具一条");
    }

    /** 物理文件已不在（例如被人工删过）时，彻底删除仍应成功（不因缺文件而失败） */
    @Test
    void purgeSucceedsWhenFileAlreadyMissing() {
        try {
            storageService.delete(purgedFileKey);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ToolBatchDTO dto = new ToolBatchDTO();
        dto.setIds(List.of(String.valueOf(purgedToolId)));
        toolPurgeService.purge(dto);   // 不得抛异常

        assertNull(toolMapper.selectById(purgedToolId));
    }

    /** 清空回收站：全部已删除工具被彻底删除，未删除的不受影响 */
    @Test
    void purgeAllEmptiesTrashOnly() {
        toolPurgeService.purgeAll();

        assertNull(toolMapper.selectById(purgedToolId), "回收站里的应被清掉");
        assertEquals(0, versionCountOf(purgedToolId), "被清掉工具的版本记录应一并删除");
        assertEquals(1, toolVersionMapper.selectCount(null).intValue(), "只剩线上工具的版本");
        assertEquals(0, toolTrashService.deletedPage(1, 20, null).getTotal(), "回收站应为空");
        assertTrue(storageService.exists(normalFileKey), "未删除工具的文件必须还在");
    }

    /** 空回收站再清空 → 成功（契约：空回收站 → code = 0） */
    @Test
    void purgeAllOnEmptyTrashSucceeds() {
        toolPurgeService.purgeAll();
        toolPurgeService.purgeAll();   // 第二次不得报错
        assertEquals(0, toolTrashService.deletedPage(1, 20, null).getTotal());
    }

    /** 批量为空 → 1003（与 batch-delete 同一口径） */
    @Test
    void purgeWithEmptyIdsRejected() {
        ToolBatchDTO dto = new ToolBatchDTO();
        dto.setIds(List.of());
        assertEquals(Code.PARAM_INVALID, assertThrows(BusinessException.class,
                () -> toolPurgeService.purge(dto)).getCode());
    }

    /** 彻底删除后，工具名被释放（该名称可以重新建工具）—— 回收站"清空即可复用名称"的闭环 */
    @Test
    void purgedToolNameBecomesAvailable() {
        ToolBatchDTO dto = new ToolBatchDTO();
        dto.setIds(List.of(String.valueOf(purgedToolId)));
        toolPurgeService.purge(dto);

        // 名称未被占用：可以再建一个同名工具（靠 uk_tool_name 唯一键验证）
        Long again = createToolWithVersion("已删工具", saveFile());
        assertEquals("已删工具", toolMapper.selectById(again).getName());
    }

    // ---------------- 夹具 ----------------

    private int versionCountOf(Long toolId) {
        return toolVersionMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ToolVersionDO>lambdaQuery()
                        .eq(ToolVersionDO::getToolId, toolId)).intValue();
    }

    private String saveFile() {
        String key = storageService.generateKey();
        try {
            storageService.save(key, new ByteArrayInputStream(new byte[8]), 8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return key;
    }

    private Long createToolWithVersion(String name, String fileKey) {
        LocalDateTime now = LocalDateTime.now();
        ToolDO tool = new ToolDO();
        tool.setName(name);
        tool.setIntro("简介");
        tool.setFileExt("zip");
        tool.setScope(Scope.DEPT);
        tool.setDeptId(deptId);
        tool.setUploaderId(999_999L);
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
        v.setFileKey(fileKey);
        v.setFileName("a.zip");
        v.setFileExt("zip");
        v.setFileSize(8L);
        v.setUploaderId(999_999L);
        v.setDownloadCount(0);
        v.setCreateTime(now);
        toolVersionMapper.insert(v);

        tool.setLatestVersionId(v.getId());
        toolMapper.updateById(tool);
        return tool.getId();
    }
}
