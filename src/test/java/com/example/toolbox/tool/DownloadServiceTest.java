package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.dto.DeptSaveDTO;
import com.example.toolbox.dept.service.DeptService;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.dto.ToolSaveDTO;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.service.DownloadService;
import com.example.toolbox.tool.service.DownloadTokenService;
import com.example.toolbox.tool.service.DownloadTokenStore;
import com.example.toolbox.tool.service.ToolWriteService;
import com.example.toolbox.tool.vo.DownloadTokenVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 鉴权下载（B3-T9）。★ 最高风险点。
 *
 * <p>覆盖：权限与可见范围校验、Range → 206 语义、Content-Range 正确性、
 * **N-02 计数口径**（无 Range 或起点 0 才计数 / 续传不计数 / HEAD 不计数）、
 * 短时效令牌（绑定 + 一次性 + 过期）、跨部门 403、已删除 2004。
 */
class DownloadServiceTest extends IntegrationTestBase {

    @Autowired
    private DownloadService downloadService;
    @Autowired
    private DownloadTokenService downloadTokenService;
    @Autowired
    private DownloadTokenStore tokenStore;
    @Autowired
    private ToolWriteService toolWriteService;
    @Autowired
    private DeptService deptService;
    @Autowired
    private StorageService storageService;

    private Long deptA;
    private Long deptB;
    private static final long ADMIN_ID = 999_999L;

    private byte[] fileContent;
    private Long toolId;
    private Long versionId;

    @BeforeEach
    void setUpData() {
        deptA = createDept("研发部");
        deptB = createDept("市场部");
        loginAs(ADMIN_ID, "admin", deptA, PermissionCode.TOOL_UPLOAD, PermissionCode.TOOL_DOWNLOAD);

        // 造一个 100 字节、内容可预测的文件
        fileContent = new byte[100];
        for (int i = 0; i < fileContent.length; i++) {
            fileContent[i] = (byte) i;
        }
        String key = storageService.generateKey();
        try {
            storageService.save(key, new ByteArrayInputStream(fileContent), fileContent.length);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ToolSaveDTO dto = new ToolSaveDTO();
        dto.setName("下载测试工具");
        dto.setVersion("v1.0");
        dto.setIntro("简介");
        dto.setFileExt("bin");
        dto.setScope(Scope.PUBLIC);
        dto.setFileKey(key);
        dto.setFileName("测试文件.bin");
        dto.setFileSize((long) fileContent.length);
        Map<String, String> ids = toolWriteService.create(dto);
        toolId = Long.valueOf(ids.get("toolId"));
        versionId = Long.valueOf(ids.get("versionId"));
    }

    private Long createDept(String name) {
        loginAs(ADMIN_ID, "admin", null, PermissionCode.DEPT_MANAGE);
        DeptSaveDTO d = new DeptSaveDTO();
        d.setName(name);
        return Long.valueOf(deptService.create(d));
    }

    private LoginUser currentUser() {
        return LoginUser.builder().userId(ADMIN_ID).username("admin").deptId(deptA)
                .permissions(java.util.Set.of(PermissionCode.TOOL_DOWNLOAD)).build();
    }

    /** 完整下载：无 Range → 起点 0、完整长度、**计数** */
    @Test
    void fullDownloadHasNoRangeAndCounts() {
        DownloadService.DownloadPlan plan = downloadService.plan(toolId, null, currentUser(), true);

        assertEquals(0L, plan.start(), "无 Range 时起点应为 0");
        assertEquals(99L, plan.end(), "无 Range 时终点应为 size-1");
        assertEquals(100L, plan.size(), "大小应为文件实际大小");
        assertEquals(100L, plan.contentLength(), "响应体长度应为完整文件");
        assertTrue(plan.countable(), "无 Range 的完整下载应计数（N-02）");
    }

    /**
     * ★ **有界 Range 且起点为 0** → 206 部分内容。
     *
     * <p>契约 §9.5 给出的例子就是 {@code Range: bytes=0-1048575} → **206 Partial Content**，
     * 即"命中 Range 即 206"，**与起点是否为 0 无关**。
     *
     * <p>"是否 206"与"是否计数"是两个独立维度（N-02）：本请求起点为 0，
     * 按「未带 Range 或起点为 0 才计数」**仍要计数**。
     *
     * <p>2026-09-20 修正：原实现只支持开放式区间（{@code bytes=N-}），把有界区间一律
     * 当完整下载返回 200，与契约 §9.5 的例子不符。本用例随之由「起点 0 按完整响应处理」
     * 改为「起点 0 也走 206」——**是口径纠正，不是放宽断言**（终值与长度都更精确）。
     */
    @Test
    void boundedRangeFromZeroIsPartialAndCounts() {
        DownloadService.DownloadPlan plan =
                downloadService.planWithRange(toolId, null, "bytes=0-49", currentUser(), true);

        assertTrue(plan.ranged(), "有界 Range 命中即应为 206（契约 §9.5 例子 bytes=0-1048575）");
        assertEquals(0L, plan.start());
        assertEquals(49L, plan.end(), "终点应取请求终点，而非文件末尾");
        assertEquals(50L, plan.contentLength(), "应只返回请求区间的 50 字节");
        assertTrue(plan.countable(), "起点为 0 应计数（N-02）");
    }

    /** 有界 Range 且起点 &gt; 0 → 206，且**不计数**（N-02 核心） */
    @Test
    void boundedRangeWithNonZeroStartDoesNotCount() {
        DownloadService.DownloadPlan plan =
                downloadService.planWithRange(toolId, null, "bytes=20-29", currentUser(), true);

        assertTrue(plan.ranged());
        assertEquals(20L, plan.start());
        assertEquals(29L, plan.end());
        assertEquals(10L, plan.contentLength());
        assertEquals(false, plan.countable(), "起点>0 的区间请求不得计数（N-02）");
    }

    /** 终点越界（≥ size）→ 按 RFC 9110 收敛到末字节，仍为 206（不是忽略、也不是 416） */
    @Test
    void rangeEndBeyondSizeClampedToLastByte() {
        DownloadService.DownloadPlan plan =
                downloadService.planWithRange(toolId, null, "bytes=50-9999", currentUser(), true);

        assertTrue(plan.ranged(), "终点越界应收敛而非忽略");
        assertEquals(50L, plan.start());
        assertEquals(99L, plan.end(), "终点应收敛为 size-1");
        assertEquals(50L, plan.contentLength());
        assertEquals(false, plan.countable());
    }

    /** 非法有界 Range（区间倒置 / 终点非数字）→ 忽略 Range，退化为完整下载 */
    @Test
    void invalidBoundedRangeIgnored() {
        for (String bad : new String[]{"bytes=80-20", "bytes=10-abc", "bytes=0--1"}) {
            DownloadService.DownloadPlan plan =
                    downloadService.planWithRange(toolId, null, bad, currentUser(), true);
            assertEquals(false, plan.ranged(), "非法区间应被忽略：" + bad);
            assertEquals(0L, plan.start(), "忽略后应从头下载：" + bad);
            assertEquals(100L, plan.contentLength(), "忽略后应按完整文件返回：" + bad);
        }
    }

    /**
     * 前端"失败详情探测"使用的 {@code bytes=1-1}：应精确返回 **1 字节**且**不计数**（N-02）。
     *
     * <p>修正前该请求实际会返回"第 1 字节到文件末尾"（近整包），只是靠客户端立即
     * {@code body.cancel()} 兜住 —— 探测成本与文件大小成正比，属隐患。
     */
    @Test
    void singleByteProbeReturnsOneByteAndDoesNotCount() throws Exception {
        DownloadService.DownloadPlan plan =
                downloadService.planWithRange(toolId, null, "bytes=1-1", currentUser(), true);

        assertTrue(plan.ranged());
        assertEquals(1L, plan.contentLength(), "bytes=1-1 应只返回 1 字节");
        assertEquals(false, plan.countable(), "起点>0 不计数（N-02）");
        try (var in = downloadService.openStream(plan)) {
            byte[] got = in.readAllBytes();
            assertEquals(1, got.length, "应只读到 1 字节，不得拖出整包");
            assertEquals((byte) 1, got[0], "应恰好是原文件第 1 字节");
        }
    }

    /** ★ Range 起点 > 0（续传）→ **不计数**（N-02 核心） */
    @Test
    void resumeWithNonZeroStartDoesNotCount() {
        DownloadService.DownloadPlan plan =
                downloadService.planWithRange(toolId, null, "bytes=50-", currentUser(), true);

        assertTrue(plan.ranged());
        assertEquals(50L, plan.start(), "续传起点应解析为 50");
        assertEquals(99L, plan.end());
        assertEquals(50L, plan.contentLength(), "应只返回剩余 50 字节");
        assertEquals(false, plan.countable(), "续传（起点>0）不得计数（N-02）");
    }

    /** HEAD 探测 → **不计数**（N-02） */
    @Test
    void headDoesNotCount() {
        DownloadService.DownloadPlan plan =
                downloadService.planWithRange(toolId, null, null, currentUser(), false);

        assertEquals(100L, plan.size(), "HEAD 应能拿到文件总大小（契约 §9.5）");
        assertEquals(false, plan.countable(), "HEAD 一律不计数（N-02）");
    }

    /** 计数实际累加：工具与版本各 +1（原子自增） */
    @Test
    void increaseCountUpdatesToolAndVersion() {
        DownloadService.DownloadPlan plan = downloadService.plan(toolId, null, currentUser(), true);
        downloadService.increaseCount(plan);

        ToolDO tool = toolMapper.selectById(toolId);
        ToolVersionDO version = toolVersionMapper.selectById(versionId);
        assertEquals(1, tool.getDownloadCount(), "工具下载数 +1");
        assertEquals(1, version.getDownloadCount(), "版本下载数 +1");

        // 再计一次 → 2（验证自增语义而非覆盖）
        downloadService.increaseCount(plan);
        assertEquals(2, toolMapper.selectById(toolId).getDownloadCount());
    }

    /**
     * 有界 Range 流式读取：**只读到请求区间**（10..19 共 10 字节），一个字节都不多。
     *
     * <p>2026-09-20 修正：原实现忽略终点、一路读到文件末尾（90 字节）。本用例随之收紧为
     * 精确区间——**是断言变强，不是放宽**。
     */
    @Test
    void boundedRangeStreamReturnsExactSlice() throws Exception {
        DownloadService.DownloadPlan plan =
                downloadService.planWithRange(toolId, null, "bytes=10-19", currentUser(), true);

        assertEquals(10L, plan.contentLength());
        try (var in = downloadService.openStream(plan)) {
            byte[] got = in.readAllBytes();
            assertEquals(10, got.length, "有界区间 10..19 应只读 10 字节");
            assertEquals((byte) 10, got[0], "首字节应为原文件第 10 字节");
            assertEquals((byte) 19, got[9], "末字节应为原文件第 19 字节");
        }
    }

    /** 开放式 Range（断点续传所用形式）：从起点读到文件末尾 */
    @Test
    void openEndedRangeStreamReturnsTail() throws Exception {
        DownloadService.DownloadPlan plan =
                downloadService.planWithRange(toolId, null, "bytes=10-", currentUser(), true);

        assertTrue(plan.ranged());
        assertEquals(90L, plan.contentLength(), "起点 10 到末尾应读 90 字节");
        try (var in = downloadService.openStream(plan)) {
            byte[] got = in.readAllBytes();
            assertEquals(90, got.length);
            assertEquals((byte) 10, got[0], "首字节应为原文件第 10 字节");
            assertEquals((byte) 99, got[89], "末字节应为原文件第 99 字节");
        }
    }

    /** 完整流式读取：内容与源文件一致 */
    @Test
    void fullStreamMatchesSource() throws Exception {
        DownloadService.DownloadPlan plan = downloadService.plan(toolId, null, currentUser(), true);
        try (var in = downloadService.openStream(plan)) {
            assertArrayEquals(fileContent, in.readAllBytes(), "完整下载内容应与源文件一致");
        }
    }

    /** 指定版本下载：版本不属于该工具 → 1004（防跨工具取文件） */
    @Test
    void versionFromAnotherToolRejected() {
        // 再建一个工具，拿它的版本 ID 去请求第一个工具的下载
        String key2 = storageService.generateKey();
        try {
            storageService.save(key2, new ByteArrayInputStream(new byte[8]), 8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ToolSaveDTO other = new ToolSaveDTO();
        other.setName("另一个工具");
        other.setVersion("v1.0");
        other.setIntro("简介");
        other.setFileExt("bin");
        other.setScope(Scope.PUBLIC);
        other.setFileKey(key2);
        other.setFileName("other.bin");
        other.setFileSize(8L);
        Map<String, String> otherIds = toolWriteService.create(other);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadService.plan(toolId, otherIds.get("versionId"), currentUser(), true));
        assertEquals(Code.TOOL_DELETED, ex.getCode(),
                "跨工具的版本 ID 应视为不存在（下载链路收敛 2004，B5 T2）");
    }

    /** 已删除工具 → 2004 */
    @Test
    void deletedToolDownloadRejected() {
        ToolDO tool = toolMapper.selectById(toolId);
        tool.setDeleted(1);
        toolMapper.updateById(tool);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadService.plan(toolId, null, currentUser(), true));
        assertEquals(Code.TOOL_DELETED, ex.getCode(), "已删除工具不可下载 → 2004");
    }

    /** 他部门 DEPT 工具不可下载 → 403 */
    @Test
    void otherDeptDownloadForbidden() {
        // 改成 DEPT 范围
        ToolDO tool = toolMapper.selectById(toolId);
        tool.setScope(Scope.DEPT);
        toolMapper.updateById(tool);

        LoginUser userB = LoginUser.builder().userId(888_888L).username("userB").deptId(deptB)
                .permissions(java.util.Set.of(PermissionCode.TOOL_DOWNLOAD)).build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadService.plan(toolId, null, userB, true));
        assertEquals(Code.FORBIDDEN, ex.getCode(), "跨部门下载应 403（P0 数据隔离）");
    }

    /** 全局管理员可跨部门下载 */
    @Test
    void globalAdminCanDownloadCrossDept() {
        ToolDO tool = toolMapper.selectById(toolId);
        tool.setScope(Scope.DEPT);
        toolMapper.updateById(tool);

        LoginUser adminB = LoginUser.builder().userId(888_888L).username("adminB").deptId(deptB)
                .permissions(java.util.Set.of(PermissionCode.TOOL_DOWNLOAD, PermissionCode.SYS_SETTING))
                .build();

        DownloadService.DownloadPlan plan = downloadService.plan(toolId, null, adminB, true);
        assertNotNull(plan, "全局管理员应可跨部门下载（N-04）");
    }

    /** 不存在的工具 → 1004 */
    @Test
    void missingToolDownloadRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadService.plan(999999L, null, currentUser(), true));
        assertEquals(Code.TOOL_DELETED, ex.getCode(),
                "不存在的工具应收敛为 2004（下载链路 B5 T2）");
    }

    /** Range 超出文件范围 → 忽略 Range，退化为完整下载（不 416） */
    @Test
    void rangeBeyondSizeFallsBackToFull() {
        DownloadService.DownloadPlan plan =
                downloadService.planWithRange(toolId, null, "bytes=500-", currentUser(), true);

        assertEquals(false, plan.ranged(), "越界 Range 应被忽略");
        assertEquals(0L, plan.start());
        assertEquals(100L, plan.contentLength());
    }

    /** 非法 Range 格式 → 忽略，退化为完整下载 */
    @Test
    void malformedRangeIgnored() {
        for (String bad : new String[]{"bytes=abc-", "not-a-range", "bytes=-50", "bytes=0-1,5-6"}) {
            DownloadService.DownloadPlan plan =
                    downloadService.planWithRange(toolId, null, bad, currentUser(), true);
            assertEquals(0L, plan.start(), "非法 Range 应忽略并从头下载：" + bad);
        }
    }

    // ---------------- 短时效令牌（§9.7） ----------------

    /** 换取令牌：绑定用户 + 工具 + 版本，expiresIn ≤ 60 */
    @Test
    void issueTokenBindsUserToolVersion() {
        DownloadTokenVO vo = downloadTokenService.issue(toolId, null);

        assertNotNull(vo.getToken(), "应返回令牌");
        assertTrue(vo.getExpiresIn() <= 60, "有效期应 ≤60 秒，实际：" + vo.getExpiresIn());

        // 校验并消费：绑定正确则通过
        DownloadTokenStore.Binding binding = tokenStore.consume(vo.getToken(), toolId, versionId);
        assertEquals(ADMIN_ID, binding.userId());
        assertEquals(toolId, binding.toolId());
        assertEquals(versionId, binding.versionId());
    }

    /** 令牌一次性：第二次消费失败（401） */
    @Test
    void tokenIsSingleUse() {
        DownloadTokenVO vo = downloadTokenService.issue(toolId, null);
        tokenStore.consume(vo.getToken(), toolId, versionId);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tokenStore.consume(vo.getToken(), toolId, versionId));
        assertEquals(Code.UNAUTHENTICATED, ex.getCode(), "令牌应一次性失效");
    }

    /** 令牌绑定校验：挪用到别的工具 / 版本 → 401 */
    @Test
    void tokenMismatchRejected() {
        DownloadTokenVO vo = downloadTokenService.issue(toolId, null);

        BusinessException wrongTool = assertThrows(BusinessException.class,
                () -> tokenStore.consume(vo.getToken(), 12345L, versionId));
        assertEquals(Code.UNAUTHENTICATED, wrongTool.getCode(), "令牌不得挪用到其他工具");

        // 上面已消费，重新签发再试版本不匹配
        DownloadTokenVO vo2 = downloadTokenService.issue(toolId, null);
        BusinessException wrongVersion = assertThrows(BusinessException.class,
                () -> tokenStore.consume(vo2.getToken(), toolId, 99999L));
        assertEquals(Code.UNAUTHENTICATED, wrongVersion.getCode(), "令牌不得挪用到其他版本");
    }

    /** 不存在 / 空令牌 → 401 */
    @Test
    void unknownTokenRejected() {
        for (String bad : new String[]{null, "", "  ", "not-a-real-token"}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> tokenStore.consume(bad, toolId, versionId),
                    "非法令牌应 401：" + bad);
            assertEquals(Code.UNAUTHENTICATED, ex.getCode());
        }
    }

    /** 令牌签发前校验可见范围：跨部门 DEPT 工具不可换取令牌（403） */
    @Test
    void issueTokenRespectsVisibility() {
        ToolDO tool = toolMapper.selectById(toolId);
        tool.setScope(Scope.DEPT);
        toolMapper.updateById(tool);

        loginAs(888_888L, "userB", deptB, PermissionCode.TOOL_DOWNLOAD);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadTokenService.issue(toolId, null));
        assertEquals(Code.FORBIDDEN, ex.getCode(), "不可见工具不得换取令牌");
    }

    /** 令牌签发：工具已删除 → 2004 */
    @Test
    void issueTokenForDeletedToolRejected() {
        ToolDO tool = toolMapper.selectById(toolId);
        tool.setDeleted(1);
        toolMapper.updateById(tool);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadTokenService.issue(toolId, null));
        assertEquals(Code.TOOL_DELETED, ex.getCode());
    }

    /** 令牌签发：版本不属于该工具 → 1004 */
    @Test
    void issueTokenWrongVersionRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloadTokenService.issue(toolId, "999999"));
        assertEquals(Code.NOT_FOUND, ex.getCode());
    }
}
