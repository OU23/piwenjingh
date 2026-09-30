package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.tool.entity.ToolDO;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 下载地址签发的**失败与边界**（M-22 / B13 验收 A8 · A9 · A14）。
 *
 * <p>两条必须同时成立的硬约束：
 * <ol>
 *   <li><b>鉴权先于签发</b>：不可见 / 已删除 / 文件缺失 → 一律不签发，且**不计数**；</li>
 *   <li><b>E-02 隔离</b>：OSS 自身故障必须对外表现为 {@code 2006}（HTTP 200），
 *       **不得**被误判为「文件不存在」（2005 / 404）。</li>
 * </ol>
 *
 * <p>分流与计数口径见 {@link DownloadUrlPlanTest}；夹具见 {@link DownloadUrlTestBase}。
 */
class DownloadUrlFailureTest extends DownloadUrlTestBase {

    // ---------------- A8 鉴权先于签发（不签发 + 不计数） ----------------

    @Test
    void invisibleToolIsRejectedAndNotCounted() {
        makeDeptScoped(localToolId);
        int before = toolMapper.selectById(localToolId).getDownloadCount();

        LoginUser otherDept = LoginUser.builder().userId(888_888L).username("userB").deptId(deptB)
                .permissions(Set.of(PermissionCode.TOOL_DOWNLOAD)).build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadUrlService.planUrl(localToolId, null, otherDept));
        assertEquals(Code.FORBIDDEN, ex.getCode(), "不可见工具不得签发地址（A8）");
        assertEquals(before, toolMapper.selectById(localToolId).getDownloadCount(), "签发失败不得计数（A9）");
    }

    @Test
    void deletedToolIsRejectedAndNotCounted() {
        ToolDO tool = toolMapper.selectById(localToolId);
        tool.setDeleted(1);
        toolMapper.updateById(tool);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadUrlService.planUrl(localToolId, null, currentUser()));
        assertEquals(Code.TOOL_DELETED, ex.getCode(), "已删除工具 → 2004（与 §9.5 同口径）");
        assertEquals(0, toolMapper.selectById(localToolId).getDownloadCount());
    }

    @Test
    void missingPhysicalFileIsRejectedAndNotCounted() {
        // DB 有记录、文件已丢（造法：正常建好后删掉物理文件）
        Map<String, String> broken = createToolWithFile("文件已丢", "gone.bin");
        Long brokenToolId = Long.valueOf(broken.get("toolId"));
        dropLocalFile(Long.valueOf(broken.get("versionId")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadUrlService.planUrl(brokenToolId, null, currentUser()));
        assertEquals(Code.FILE_KEY_INVALID, ex.getCode(), "物理文件缺失 → 2005（不得签发地址）");
        assertEquals(0, toolMapper.selectById(brokenToolId).getDownloadCount(), "失败不计数");
    }

    @Test
    void versionOfAnotherToolIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadUrlService.planUrl(localToolId, String.valueOf(ossVersionId), currentUser()));
        assertEquals(Code.TOOL_DELETED, ex.getCode(), "跨工具的版本 ID 视为不存在");
    }

    // ---------------- A14 / E-02：OSS 故障不得误判为「文件不存在」 ----------------

    @Test
    void ossProbeFailureSurfacesAs2006NotMissingFile() {
        // 真实 OSS 实现会把自己的异常转成 2006；这里直接让桩抛 2006，验证上层不会被误判
        when(ossStorageService.exists(anyString()))
                .thenThrow(new BusinessException(Code.STORAGE_OSS_UNAVAILABLE, "对象存储暂不可用"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadUrlService.planUrl(ossToolId, null, currentUser()));
        assertEquals(Code.STORAGE_OSS_UNAVAILABLE, ex.getCode(),
                "OSS 探测失败必须是 2006，绝不能收敛成「文件不存在」的 2005/404");
        assertEquals(200, ex.getCode().getHttpStatus(), "对外 HTTP 200 + 业务码，不是 500");
        assertEquals(0, toolMapper.selectById(ossToolId).getDownloadCount(), "失败不计数");
    }

    /** 防御性分支：OSS 分支拿不到签名 URL → 2006（而不是返回空白地址） */
    @Test
    void emptySignedUrlIsReportedAs2006() {
        when(ossStorageService.exists(anyString())).thenReturn(true);
        when(ossStorageService.presignedUrl(anyString(), anyString(), any())).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadUrlService.planUrl(ossToolId, null, currentUser()));
        assertEquals(Code.STORAGE_OSS_UNAVAILABLE, ex.getCode());
        assertEquals(0, toolMapper.selectById(ossToolId).getDownloadCount());
    }
}
