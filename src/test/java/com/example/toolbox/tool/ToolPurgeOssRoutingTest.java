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

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归测试：OSS 落点的"彻底删除"按**逻辑删除**处理 —— **不删 OSS 对象，只清库记录**。
 *
 * <p>★ 沿革（两次变更都记在这里，便于回溯）：
 * <ul>
 *   <li>2026-10-15：修掉 {@code insertVersion} 漏写 {@code storage_backend}（否则 OSS 文件被记成
 *       {@code local}，删除被路由到本地实现、含斜杠键判"非法"后静默跳过）—— 断言 ① 仍守这一点。</li>
 *   <li><b>2026-10-16：OSS 落点改为逻辑删除</b> —— 后端改用 STS 角色访问 OSS，而角色暂无
 *       {@code oss:DeleteObject} 权限，物理删除必 403。故**保留对象、只删库记录**（断言 ② 改为"不得删除"）。</li>
 * </ul>
 *
 * <p>本类用真实 {@link StorageService}（路由） + {@code mockConstruction} 桩住
 * {@code new OSSClientBuilder().build(...)}（不打真实 OSS），走完整链路：
 * 创建（落点记 oss）→ 软删 → 清空回收站，断言：
 * ① 落点字段 = oss；② **OSS {@code deleteObject} 未被调用**（逻辑删除）；③ 库记录消失；④ 回收站为空。
 *
 * <p><b>熔断（P0）</b>：把删除改回物理删除（或回退 {@code storage_backend} 修复）→
 * {@link #purgeKeepsOssObjectButRemovesRecords} 必红。
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
    void purgeKeepsOssObjectButRemovesRecords() {
        loginAs(1L, "admin", 1L, PermissionCode.TOOL_DELETE);
        // 夹具对齐（非断言削弱）：2026-10-16 起所有对象操作统一走 STS，故读/写/删签发方法都要桩上；
        // 过期时间改用相对值，避免写死时间戳变成"时间炸弹"。
        StsCredential credential = new StsCredential("STS-ak", "STS-sk", "STS-token",
                Instant.now().plus(Duration.ofHours(2)).toString());
        when(credentialIssuer.issueForRead(anyString())).thenReturn(credential);
        when(credentialIssuer.issueForVerification(anyString())).thenReturn(credential);
        when(credentialIssuer.issueForWrite(anyString())).thenReturn(credential);
        when(credentialIssuer.issueForDelete(anyString())).thenReturn(credential);

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
            verify(credentialIssuer).issueForRead(fileKey);

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

            // ③ 逻辑删除：OSS 对象**不得**被删除（2026-10-16 起角色无 oss:DeleteObject，物理删除必 403）
            verify(captured[0], never()).deleteObject(anyString(), anyString());

            // ④ 库记录消失，且不再出现在回收站（页面刷新后文件应消失、不进已删除列表）
            assertNull(toolMapper.selectById(toolId), "工具记录应被彻底删除");
            assertEquals(0, toolTrashService.deletedPage(1, 20, null).getTotal(), "回收站应已清空");
        }
    }
}
