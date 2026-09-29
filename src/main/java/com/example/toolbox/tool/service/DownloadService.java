package com.example.toolbox.tool.service;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.mapper.ToolMapper;
import com.example.toolbox.tool.mapper.ToolVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * 鉴权下载（契约 §9.5 + N-02）。★ 本阶段最高风险点。
 *
 * <p>关键约束：
 * <ul>
 *   <li>**R4 流式**：下载**不把文件读入内存**，分片读取用 {@code skip} + 限量拷贝；</li>
 *   <li>**R5 禁直链**：文件只能经本接口按鉴权结果返回，物理路径从不外泄；</li>
 *   <li>**R3 可见范围**：判定走 {@code ToolService.requireVisibleTool}，不在此重写；</li>
 *   <li>**计数口径 N-02**：仅「无 Range，或 Range 起点为 0」计数；续传不计数；HEAD 不计数；失败不计数。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    /** 限量拷贝缓冲区（8 KB；与文件大小无关，保证 R4） */
    private static final int BUFFER_SIZE = 8192;

    private final ToolMapper toolMapper;
    private final ToolVersionMapper toolVersionMapper;
    private final StorageService storageService;
    private final ToolService toolService;

    /**
     * 下载准备结果：解析出的版本 + Range 语义。
     *
     * @param version  目标版本
     * @param tool     所属工具
     * @param start    Range 起点（无 Range 时为 0）
     * @param end      Range 终点（**含**；有界 Range 取请求的终点并收敛到末字节；无 Range 时为 size-1）
     * @param size     文件总大小
     * @param ranged   本次是否为 **206 部分内容**响应（请求带了有效且被接受的 Range）
     * @param countable 本次请求是否应计数（N-02：起点为 0 或未带 Range 才计数）
     */
    public record DownloadPlan(ToolDO tool, ToolVersionDO version,
                               long start, long end, long size, boolean ranged, boolean countable) {

        /** 本次响应体长度 */
        public long contentLength() {
            return end - start + 1;
        }
    }

    /**
     * 解析下载计划（含权限与可见范围校验）。
     *
     * @param counting 是否参与计数判断（HEAD 请求传 false —— N-02：HEAD 一律不计数）
     */
    public DownloadPlan plan(Long toolId, String versionId, LoginUser user, boolean counting) {
        ToolDO tool = requireVisibleForDownload(toolId, user);
        ToolVersionDO version = resolveVersion(tool, versionId);

        long size;
        try {
            // 相位：exists 必须在 size 之前、且在 Controller 写响应头之前完成（B5 T2）
            if (!storageService.exists(version.getFileKey())) {
                throw new BusinessException(Code.FILE_KEY_INVALID, "文件不存在或已被清理");  // 2005
            }
            size = storageService.size(version.getFileKey());
        } catch (IOException e) {
            // 存储根不可用 / 读取失败 → 2003（配置类，不适用 404 规则）
            throw new BusinessException(Code.STORAGE_UNAVAILABLE, "文件不可读：" + e.getMessage());
        }

        // 无 Range：完整文件，起点 0
        boolean countable = counting;
        return new DownloadPlan(tool, version, 0L, size - 1, size, false, countable);
    }

    /**
     * 解析带 Range 的下载计划（N-02 计数口径在此收口）。
     *
     * <p>支持的 Range 形式（契约 §9.5 只规定单段 byte range）：
     * <ul>
     *   <li>{@code bytes=start-end} —— **有界区间**。契约给出的例子
     *       {@code bytes=0-1048575} 即此形式 → 206 +
     *       {@code Content-Range: bytes 0-1048575/总长}；</li>
     *   <li>{@code bytes=start-} —— 开放式区间（起点到文件末尾），断点续传即用此形式。</li>
     * </ul>
     * 多段（含逗号）、后缀形式（{@code bytes=-N}）、格式非法、区间倒置、起点越界，一律**忽略该 Range**，
     * 按完整下载（200）响应 —— 比 416 更宽容，客户端可自然降级。
     *
     * <p>计数规则（N-02）：**仅当「未带 Range」或「Range 起点为 0」时计 1 次**；
     * 起点 &gt; 0 的续传请求不计数。故 {@code bytes=0-49} 这类"从头取一段"仍计数。
     */
    public DownloadPlan planWithRange(Long toolId, String versionId, String rangeHeader,
                                      LoginUser user, boolean counting) {
        ToolDO tool = requireVisibleForDownload(toolId, user);
        ToolVersionDO version = resolveVersion(tool, versionId);

        long size;
        try {
            // 相位：exists 必须在 size 之前、且在 Controller 写响应头之前完成（B5 T2）
            if (!storageService.exists(version.getFileKey())) {
                throw new BusinessException(Code.FILE_KEY_INVALID, "文件不存在或已被清理");  // 2005
            }
            size = storageService.size(version.getFileKey());
        } catch (IOException e) {
            // 存储根不可用 / 读取失败 → 2003（配置类，不适用 404 规则）
            throw new BusinessException(Code.STORAGE_UNAVAILABLE, "文件不可读：" + e.getMessage());
        }

        if (rangeHeader == null || rangeHeader.isBlank()) {
            return new DownloadPlan(tool, version, 0L, size - 1, size, false, counting);
        }

        // Range 解析收口在 RangeSpec（纯函数，与调用方语义解耦）
        RangeSpec range = RangeSpec.parse(rangeHeader, size);
        if (range == null) {
            // 被忽略的 Range：**必须按完整下载（200）响应**，否则会发出与实际内容
            // 不匹配的 Content-Range。起点按 0 处理 → 计入一次新下载（N-02）。
            return new DownloadPlan(tool, version, 0L, size - 1, size, false, counting);
        }

        // N-02：起点为 0（含未带 Range）计数；起点 > 0 的续传不计数
        boolean countable = counting && range.start() == 0;
        return new DownloadPlan(tool, version, range.start(), range.end(), size, true, countable);
    }

    /**
     * 按计划打开**限量**输入流（R4：不把整文件读入内存）。
     * 调用方负责关闭。
     */
    public InputStream openStream(DownloadPlan plan) {
        try {
            InputStream raw = storageService.open(plan.version().getFileKey());
            // 跳过 Range 起点（大文件用 skip 大跨度跳过，不循环读入内存）
            if (plan.start() > 0) {
                skipFully(raw, plan.start());
            }
            return new LimitedInputStream(raw, plan.contentLength(), BUFFER_SIZE);
        } catch (IOException e) {
            throw new BusinessException(Code.STORAGE_UNAVAILABLE, "文件读取失败：" + e.getMessage());
        }
    }

    /**
     * 累加下载计数（工具与版本各 +1）。
     * **仅在** {@code plan.countable()} 为 true 时由调用方触发；用原子自增避免并发丢计数。
     */
    public void increaseCount(DownloadPlan plan) {
        toolMapper.incrementDownloadCount(plan.tool().getId());
        toolVersionMapper.incrementDownloadCount(plan.version().getId());
    }

    // ---------------- 内部 ----------------

    /**
     * 下载链路专用的「可见范围」判定（B5 T2 + M-17）。
     *
     * <p>复用 {@link ToolService#requireVisibleForRead}（R3 唯一入口 + M-17 定向授权例外，
     * **不重写判定**），仅在其之外把「工具不存在」（{@code 1004}）收敛为 {@code 2004}，
     * 使下载链路对「资源已不存在」统一口径（与契约 v1.11 / 对方实现一致）。
     * 已删除（2004）、不可见（403）原样透传。
     */
    public ToolDO requireVisibleForDownload(Long toolId, LoginUser user) {
        try {
            return toolService.requireVisibleForRead(toolId, user);
        } catch (BusinessException e) {
            if (e.getCode() == Code.NOT_FOUND) {
                throw new BusinessException(Code.TOOL_DELETED, e.getMessage());
            }
            throw e;
        }
    }

    /**
     * 解析目标版本（**下载链路专用**，仅在 {@link #plan} / {@link #planWithRange} 内调用）。
     * 不传 versionId 取最新版；指定的版本必须属于该工具。
     *
     * <p>B5 T2：下载链路内「资源已不存在」统一收敛为 {@code 2004}（与契约 v1.11 / 对方实现一致）。
     */
    private ToolVersionDO resolveVersion(ToolDO tool, String versionId) {
        if (versionId == null || versionId.isBlank()) {
            if (tool.getLatestVersionId() == null || tool.getLatestVersionId() <= 0) {
                throw new BusinessException(Code.TOOL_DELETED, "工具暂无可用版本");
            }
            ToolVersionDO v = toolVersionMapper.selectById(tool.getLatestVersionId());
            if (v == null) {
                throw new BusinessException(Code.TOOL_DELETED, "版本不存在");
            }
            return v;
        }
        Long vid;
        try {
            vid = Long.valueOf(versionId.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(Code.PARAM_INVALID, "版本 ID 格式非法");
        }
        ToolVersionDO v = toolVersionMapper.selectById(vid);
        // 版本不存在，或不属于该工具（防越权拿别的工具的文件）→ 2004（下载链路收敛）
        if (v == null || !v.getToolId().equals(tool.getId())) {
            throw new BusinessException(Code.TOOL_DELETED, "版本不存在");
        }
        return v;
    }

    /** 跳过大跨度字节（大文件不能用循环读丢弃 —— 那等于把前面的数据读进内存） */
    private void skipFully(InputStream in, long toSkip) throws IOException {
        long remaining = toSkip;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                // 某些流 skip 不可靠，退化为读 1 字节
                if (in.read() < 0) {
                    break;
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    /**
     * 限量输入流：最多读 {@code limit} 字节后返回 EOF。
     * 保证 Range 响应不会多加内容，同时保持流式（不预读入内存）。
     */
    private static final class LimitedInputStream extends InputStream {

        private final InputStream delegate;
        private final int bufferSize;
        private long remaining;

        LimitedInputStream(InputStream delegate, long limit, int bufferSize) {
            this.delegate = delegate;
            this.remaining = limit;
            this.bufferSize = bufferSize;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = delegate.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int read = delegate.read(b, off, toRead);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(delegate.available(), remaining);
        }
    }

    /** 供调用方判断文件是否还在（HEAD 场景下可选使用） */
    public Optional<Long> sizeOf(ToolVersionDO version) {
        try {
            return Optional.of(storageService.size(version.getFileKey()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
