package com.example.toolbox.upload.service;

import com.example.toolbox.common.log.AuditLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 临时分片超时清理（契约 §8.4 / §13.5：未合并分片保留 **24 小时**）。
 *
 * <p>保留时长走配置项（原则 9：不硬编码），默认 24 小时。
 * 清理动作留日志（原则 10 + 豁免 S-03 应用日志口径）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkCleanupTask {

    private final ChunkStorage chunkStorage;
    private final AuditLogger auditLogger;

    /** 保留时长（小时），契约 §13.5 默认 24 */
    @Value("${toolbox.upload.chunk-retention-hours:24}")
    private long retentionHours;

    /** 每小时检查一次（分片保留 24h，小时级频率足够且开销可忽略） */
    @Scheduled(fixedDelayString = "${toolbox.upload.cleanup-interval-ms:3600000}")
    public void cleanExpiredChunks() {
        Path root = chunkStorage.chunkRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant deadline = Instant.now().minus(retentionHours, ChronoUnit.HOURS);
        int[] removed = {0};

        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isDirectory).forEach(dir -> {
                if (isExpired(dir, deadline)) {
                    if (deleteRecursively(dir)) {
                        removed[0]++;
                    }
                }
            });
        } catch (IOException e) {
            log.warn("[CLEANUP] 分片目录扫描失败：{}", e.getMessage());
            return;
        }

        if (removed[0] > 0) {
            // 记录清理动作（不记录具体文件内容）
            auditLogger.write("system", "CHUNK_CLEANUP",
                    "dirs:" + removed[0] + " retentionHours:" + retentionHours, "OK");
        }
    }

    /** 目录是否超期：以最后修改时间判断（分片写入会更新该目录的 mtime） */
    private boolean isExpired(Path dir, Instant deadline) {
        try {
            return Files.getLastModifiedTime(dir).toInstant().isBefore(deadline);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 单个文件删除失败不阻断整体清理
                }
            });
            return true;
        } catch (IOException e) {
            log.warn("[CLEANUP] 目录删除失败 dir={} err={}", dir.getFileName(), e.getMessage());
            return false;
        }
    }
}
