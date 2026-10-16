package com.example.toolbox.tool;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.dto.ToolBatchDTO;
import com.example.toolbox.tool.dto.ToolSaveDTO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.service.ToolPurgeService;
import com.example.toolbox.tool.service.ToolTrashService;
import com.example.toolbox.tool.service.ToolWriteService;
import com.example.toolbox.upload.service.OssStsCredentialIssuer;
import com.example.toolbox.upload.service.StsCredential;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归测试：OSS 模式下的"彻底删除"必须真把对象从 OSS 删掉（而非路由错后端被静默吞掉）。
 *
 * <p>★ 贴合线上症状："删工具 + 清空回收站后，OSS 里对象仍在"。根因是
 * {@code insertVersion} 漏写 {@code storage_backend}，使 OSS 文件被记成 {@code local}，
 * 删除时被路由到本地实现，含斜杠的键被判"非法的存储键"后静默跳过。
 *
 * <p>本类用真实 {@link StorageService}（路由） + {@code mockConstruction} 桩住
 * {@code new OSSClientBuilder().build(...)}（不打真实 OSS），走完整链路：
 * 创建（落点记 oss）→ 软删 → 清空回收站，断言：
 * ① 落点字段 = oss；② OSS {@code deleteObject} 被调用；③ 库记录消失；④ 回收站为空。
 *
 * <p><b>熔断（P0）</b>：回退修复后，①落点变 local → ②删除路由到本地实现、OSS {@code deleteObject}
 * 不会被调用 → {@link #purgeDeletesObjectFromOssWhenBackendIsOss} 必红。
 */
@SpringBootTest
@ActiveProfiles("test")
class ToolPurgeOssRoutingTest extends IntegrationTestBase {

    private static final String BUCKET = "test-bucket";

    @DynamicPropertySource
    static void ossConfig(DynamicPropertyRegistry registry) {
        registry.add("toolbox.storage.type", () -> "oss");
        registry.add("toolbox.oss.endpoint", () -> "https://oss.example.com");
        registry.add("toolbox.oss.bucket", () -> BUCKET);
        registry.add("toolbox.oss.access-key-id", () -> "test-ak");
        registry.add("toolbox.oss.access-key-secret", () -> "test-sk");
        // oss 模式下直传临时凭证签发器（OssStsCredentialIssuer）启动即校验角色 ARN 必填
        registry.add("toolbox.oss.sts-role-arn", () -> "acs:ram::1234567890:role/test-toolbox-uploader");
    }

    @Autowired
    private ToolWriteService toolWriteService;
    @Autowired
    private ToolPurgeService toolPurgeService;
    @Autowired
    private ToolTrashService toolTrashService;
    @MockBean
    private OssStsCredentialIssuer credentialIssuer;

    @Test
    void purgeDeletesObjectFromOssWhenBackendIsOss() {
        loginAs(1L, "admin", 1L, PermissionCode.TOOL_DELETE);
        when(credentialIssuer.issueForVerification(anyString())).thenReturn(
                new StsCredential("STS-ak", "STS-sk", "STS-token", "2026-10-16T12:00:00Z"));

        OSS[] captured = new OSS[1];
        try (var ignored = mockConstruction(OSSClientBuilder.class, (builder, context) -> {
            OSS oss = mock(OSS.class);
            when(oss.doesObjectExist(anyString(), anyString())).thenReturn(true);
            when(builder.build(anyString(), anyString(), anyString())).thenReturn(oss);
            when(builder.build(anyString(), anyString(), anyString(), anyString())).thenReturn(oss);
            captured[0] = oss;
        })) {
            String fileKey = "tools/1/test-oss-file.zip";
            ToolSaveDTO dto = new ToolSaveDTO();
            dto.setName("OSS落点回归测试");
            dto.setVersion("v1.0");
            dto.setIntro("简介");
            dto.setFileExt("zip");
            dto.setScope(Scope.DEPT);
            dto.setFileKey(fileKey);
            dto.setFileName("a.zip");
            dto.setFileSize(8L);

            var result = toolWriteService.create(dto);
            verify(credentialIssuer).issueForVerification(fileKey);

            // ① 落点字段必须随写入后端记为 oss（回退修复前会落为 local）
            ToolVersionDO v = toolVersionMapper.selectOne(
                    Wrappers.<ToolVersionDO>lambdaQuery().eq(ToolVersionDO::getFileKey, fileKey));
            assertEquals("oss", v.getStorageBackend(), "OSS 模式下落点字段必须为 oss，否则删除会路由错后端");

            // ② 软删 → 彻底删除
            String toolIdStr = result.get("toolId");
            Long toolId = Long.valueOf(toolIdStr);
            toolTrashService.softDelete(toolId);
            ToolBatchDTO purgeDto = new ToolBatchDTO();
            purgeDto.setIds(java.util.List.of(toolIdStr));
            toolPurgeService.purge(purgeDto);

            // ③ OSS 对象必须真的被删除（回退修复前：路由到本地存储，键含斜杠被判非法 → 静默吞掉，永不删 OSS）
            verify(captured[0]).deleteObject(BUCKET, fileKey);

            // ④ 库记录消失，且不再出现在回收站（页面刷新后文件应消失、不进已删除列表）
            assertNull(toolMapper.selectById(toolId), "工具记录应被彻底删除");
            assertEquals(0, toolTrashService.deletedPage(1, 20, null).getTotal(), "回收站应已清空");
        }
    }
}
