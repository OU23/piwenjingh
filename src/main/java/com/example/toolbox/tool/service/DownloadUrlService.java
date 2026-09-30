package com.example.toolbox.tool.service;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.storage.OssProperties;
import com.example.toolbox.storage.StorageBackend;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.vo.DownloadTokenVO;
import com.example.toolbox.tool.vo.DownloadUrlVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 下载地址签发（契约 §9.5 附 / v1.19，M-22）—— **分流在此收口**。
 *
 * <p>规则：**按文件落点**（{@code tb_tool_version.storage_backend}）——
 * 落 OSS → 签发**短时效签名 URL**（`direct=true`，浏览器直连、字节不过后端）；
 * 落本地 → 返回后端 {@code ?token=} 下载地址（`direct=false`，仍由后端代理）。
 *
 * <p><b>为什么单独成类</b>：{@link DownloadService} 管"流式取文件"（Range / 206 / 计数口径），
 * 本类管"签发地址"（依赖 OSS 配置与令牌服务）；拆开后各自守得住行数红线，**行为不变**。
 *
 * <p><b>判权与版本解析不重写</b>（R3 单一入口）：直接复用 {@link DownloadService} 的同一实现
 * （{@code requireVisibleForDownload} / {@code resolveVersion}，二者同包可见）。
 * 鉴权与失败口径与下载接口**完全一致**：**先鉴权、后签发**，任一失败都不签发。
 * 计数：**成功签发即计 1 次**（契约 v1.19 口径修订）。
 */
@Service
@RequiredArgsConstructor
public class DownloadUrlService {

    private final DownloadService downloadService;
    private final StorageService storageService;
    private final DownloadTokenService downloadTokenService;
    private final OssProperties ossProperties;

    public DownloadUrlVO planUrl(Long toolId, String versionId, LoginUser user) {
        ToolDO tool = downloadService.requireVisibleForDownload(toolId, user);
        ToolVersionDO version = downloadService.resolveVersion(tool, versionId);

        // 与 §9.5 同相位：先探测物理文件（缺失 → 2005）。
        // 注：exists() 不抛受检 IOException；对象存储异常由实现转 2006（非受检）。
        if (!storageService.exists(version.getFileKey())) {
            throw new BusinessException(Code.FILE_KEY_INVALID, "文件不存在或已被清理");  // 2005
        }

        DownloadUrlVO vo;
        if (StorageBackend.of(version.getStorageBackend()) == StorageBackend.OSS) {
            Duration ttl = ossProperties.urlTtl();
            String url = storageService.presignedUrl(version.getFileKey(), version.getFileName(), ttl)
                    .orElseThrow(() -> new BusinessException(Code.STORAGE_OSS_UNAVAILABLE, "对象存储暂不可用"));
            vo = DownloadUrlVO.builder()
                    .url(url)
                    .expiresIn(ossProperties.urlTtlSecondsOrDefault())
                    .direct(true)
                    .build();
        } else {
            // 本地文件：后端令牌下载地址（相对路径，前端同源导航）
            DownloadTokenVO issued = downloadTokenService.issue(toolId, versionId);
            String url = "/api/tools/" + toolId + "/download?token=" + issued.getToken()
                    + (versionId == null || versionId.isBlank() ? "" : "&versionId=" + versionId);
            vo = DownloadUrlVO.builder()
                    .url(url)
                    .expiresIn(issued.getExpiresIn())
                    .direct(false)
                    .build();
        }

        // 计数口径（v1.19）：签发成功即计 1 次（直链后后端拿不到下载事件）
        downloadService.increaseCount(tool, version);
        return vo;
    }
}
