package com.example.toolbox.tool;

import com.example.toolbox.tool.vo.DownloadUrlVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载地址**分流**与计数口径（M-22 / B13 验收 A5 · A6 · A7 · A9 · C7）。
 *
 * <p>核心断言：**分流以「每个文件的实际落点」为准**（`tb_tool_version.storage_backend`），
 * 而不是全局开关 —— 混合态下本地文件与 OSS 文件必须各自得到正确的地址形态。
 *
 * <p>失败与边界用例见 {@link DownloadUrlFailureTest}；夹具见 {@link DownloadUrlTestBase}。
 */
class DownloadUrlPlanTest extends DownloadUrlTestBase {

    // ---------------- A6 本地文件：后端令牌地址 ----------------

    @Test
    void localFileGetsProxiedTokenUrl() {
        DownloadUrlVO vo = downloadUrlService.planUrl(localToolId, null, currentUser());

        assertFalse(vo.isDirect(), "本地文件必须由后端代理返回（direct=false）");
        assertTrue(vo.getUrl().startsWith("/api/tools/" + localToolId + "/download?token="),
                "本地文件应拿到后端令牌地址，实际：" + vo.getUrl());
        assertFalse(vo.getUrl().contains("http"), "本地文件不得拿到任何直链");
        assertTrue(vo.getExpiresIn() > 0 && vo.getExpiresIn() <= 60,
                "后端令牌地址有效期应 ≤60 秒（导航即时触发），实际：" + vo.getExpiresIn());
    }

    @Test
    void localFileUrlCarriesRequestedVersion() {
        DownloadUrlVO vo = downloadUrlService.planUrl(localToolId, String.valueOf(localVersionId), currentUser());

        assertTrue(vo.getUrl().contains("versionId=" + localVersionId),
                "指定版本时地址需带上版本，实际：" + vo.getUrl());
    }

    // ---------------- A5 OSS 文件：签名直链 ----------------

    @Test
    void ossFileGetsSignedDirectUrl() {
        stubOssSigned();

        DownloadUrlVO vo = downloadUrlService.planUrl(ossToolId, null, currentUser());

        assertTrue(vo.isDirect(), "OSS 文件必须走直链（direct=true），字节不过后端");
        assertEquals(SIGNED_URL, vo.getUrl());
        assertEquals(ossProperties.urlTtlSecondsOrDefault(), vo.getExpiresIn(),
                "有效期应取 toolbox.oss.url-ttl（需覆盖「暂停后续传」场景）");
    }

    // ---------------- A7 混合态 ----------------

    @Test
    void mixedBackendsResolveIndependently() {
        stubOssSigned();

        DownloadUrlVO localVo = downloadUrlService.planUrl(localToolId, null, currentUser());
        DownloadUrlVO ossVo = downloadUrlService.planUrl(ossToolId, null, currentUser());

        assertFalse(localVo.isDirect(), "混合态下本地文件仍走代理");
        assertTrue(ossVo.isDirect(), "混合态下 OSS 文件走直链");
        assertEquals(SIGNED_URL, ossVo.getUrl());
        assertTrue(localVo.getUrl().startsWith("/api/tools/"), "两条分流互不串扰");
    }

    /** C7：写入已切到 OSS 时，**未迁移的本地存量**仍照常可下载（仍由后端代理返回） */
    @Test
    void localLeftoversRemainDownloadableWhenWriteBackendIsOss() {
        storageProperties.setType("oss");
        try {
            DownloadUrlVO vo = downloadUrlService.planUrl(localToolId, null, currentUser());

            assertFalse(vo.isDirect(), "写入改 OSS 不影响本地存量：仍按落点走后端代理");
            assertTrue(vo.getUrl().startsWith("/api/tools/"), "实际：" + vo.getUrl());
        } finally {
            storageProperties.setType("local"); // 还原，避免污染同上下文的其他用例
        }
    }

    /** 落点字段为未知值（历史数据）→ 按本地处理，不得误发直链 */
    @Test
    void unknownBackendValueFallsBackToLocal() {
        markBackend(localVersionId, "unknown"); // 列宽 varchar(8)，用短值模拟历史/异常落库值

        DownloadUrlVO vo = downloadUrlService.planUrl(localToolId, null, currentUser());

        assertFalse(vo.isDirect(), "未知落点一律按本地（兼容存量数据）");
    }

    // ---------------- A9 计数口径：签发即计 1 ----------------

    @Test
    void successfulIssueCountsOnce() {
        downloadUrlService.planUrl(localToolId, null, currentUser());

        assertEquals(1, toolMapper.selectById(localToolId).getDownloadCount());
        assertEquals(1, toolVersionMapper.selectById(localVersionId).getDownloadCount());

        downloadUrlService.planUrl(localToolId, null, currentUser());
        assertEquals(2, toolMapper.selectById(localToolId).getDownloadCount(), "每次成功签发计 1");
        assertEquals(2, toolVersionMapper.selectById(localVersionId).getDownloadCount());
    }

    @Test
    void ossIssueAlsoCountsOnce() {
        stubOssSigned();

        downloadUrlService.planUrl(ossToolId, null, currentUser());

        assertEquals(1, toolMapper.selectById(ossToolId).getDownloadCount(),
                "直链后后端拿不到下载事件 → 以「签发一次 = 一次下载意图」计数（N-02 修订）");
        assertEquals(1, toolVersionMapper.selectById(ossVersionId).getDownloadCount());
    }
}
