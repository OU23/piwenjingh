package com.example.toolbox.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.storage.StorageBackend;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.dto.ToolSaveDTO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.service.ToolWriteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 回归测试：新建 / 追加版本必须把"文件实际落点"写进 {@code tb_tool_version.storage_backend}。
 *
 * <p>★ 这是 B14 / M-23 之后的真 bug 抓手：生产代码 {@code ToolWriteService.insertVersion()}
 * 曾**漏写**该字段，导致 OSS 上传的文件在库里被记成 {@code local} —— 读取 / 删除被路由到本地实现，
 * 含斜杠的 OSS 键被判"非法的存储键"并被静默吞掉，于是"删工具 + 清空回收站后 OSS 对象仍在"。
 *
 * <p>本类用 {@code @MockBean StorageService} 桩出"写入后端"，直接走真实 {@link ToolWriteService#create}，
 * 断言落库值。注意：现有 {@code ToolPurgeTest} 手工构造 {@code ToolVersionDO} 并绕过了
 * {@code insertVersion}，所以"全绿却没抓到 bug"——本类正是补这个缺口。
 *
 * <p><b>熔断（P0）</b>：临时回退修复（删掉 {@code insertVersion} 里的
 * {@code v.setStorageBackend(...)}）后，{@link #writesOssWhenWriteTargetIsOss} 必红（落库值变回 local）。
 */
class ToolWriteStorageBackendTest extends IntegrationTestBase {

    @MockBean
    private StorageService storageService;

    @Autowired
    private ToolWriteService toolWriteService;

    /** 写入后端为 oss 时，落点字段必须记 oss（否则 OSS 文件删不掉 / 签不出） */
    @Test
    void writesOssWhenWriteTargetIsOss() {
        loginAs(1L, "admin", 1L, PermissionCode.TOOL_DELETE);
        when(storageService.backend()).thenReturn(StorageBackend.OSS);
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.generateKey()).thenReturn("tools/1/abc123.zip");

        String fileKey = "tools/1/abc123.zip";
        toolWriteService.create(buildDto(fileKey));

        ToolVersionDO v = toolVersionMapper.selectOne(
                Wrappers.<ToolVersionDO>lambdaQuery().eq(ToolVersionDO::getFileKey, fileKey));
        assertEquals("oss", v.getStorageBackend(),
                "写入后端为 oss 时落点字段必须记 oss（回退修复会判定为 local → 红）");
    }

    /** 写入后端为 local 时，落点字段必须记 local（保证修复不破坏本地模式） */
    @Test
    void writesLocalWhenWriteTargetIsLocal() {
        loginAs(2L, "admin", 1L, PermissionCode.TOOL_DELETE);
        when(storageService.backend()).thenReturn(StorageBackend.LOCAL);
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.generateKey()).thenReturn("abcdef123456.zip");

        String fileKey = "abcdef123456.zip";
        toolWriteService.create(buildDto(fileKey));

        ToolVersionDO v = toolVersionMapper.selectOne(
                Wrappers.<ToolVersionDO>lambdaQuery().eq(ToolVersionDO::getFileKey, fileKey));
        assertEquals("local", v.getStorageBackend(),
                "写入后端为 local 时落点字段必须记 local");
    }

    private ToolSaveDTO buildDto(String fileKey) {
        ToolSaveDTO dto = new ToolSaveDTO();
        dto.setName("写入落点回归测试");
        dto.setVersion("v1.0");
        dto.setIntro("简介");
        dto.setFileExt("zip");
        dto.setScope(Scope.DEPT);
        dto.setFileKey(fileKey);
        dto.setFileName("a.zip");
        dto.setFileSize(8L);
        return dto;
    }
}
