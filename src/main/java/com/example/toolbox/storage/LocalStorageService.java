package com.example.toolbox.storage;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 本地磁盘存储实现（MVP 默认实现）。
 * 全部流式读写（principles.md R4），物理文件名由系统根据存储键决定（防路径穿越）。
 *
 * <p>根路径经 {@link StorageRootProvider} 获取 —— **不直接读配置项**，
 * 否则"管理员在设置页改存储路径"不会生效（B4 验收 A3 / B1）。
 */
@Component
@RequiredArgsConstructor
public class LocalStorageService implements StorageService {

    private final StorageRootProvider storageRootProvider;

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /**
     * 原子落盘的临时文件后缀（临时文件与目标**同目录**，保证同一文件系统）。
     *
     * <p>⚠️ 临时文件名**不参与** {@link #KEY_PATTERN} 校验（见 {@link #tempPathFor}）：
     * 存储键允许用满 64 字符，若"键 + 后缀"再走同一校验会被直接判为非法。
     */
    private static final String TEMP_SUFFIX = ".tmp";

    /**
     * 保存文件流（**原子落盘**，R4 流式）。
     *
     * <p>实现为「先写同目录临时文件 → 原子改名就位」，因此：
     * <ul>
     *   <li><b>正式路径只会在内容完整之后出现</b> —— 写入中途失败或进程被杀，都不会在正式路径
     *       留下"半截文件"，也不会让残缺文件被当作可下载资源对外通告；</li>
     *   <li>失败路径**清理临时文件**，不留孤儿（与 N-09「孤儿文件」口径一致）；</li>
     *   <li>同目录保证**同一文件系统**，{@code ATOMIC_MOVE} 才成立；不支持原子移动的文件系统
     *       回退为 {@code REPLACE_EXISTING}（同目录下通常仍是 rename，不产生数据拷贝）。</li>
     * </ul>
     */
    @Override
    public String save(String storageKey, InputStream data, long size) throws IOException {
        Path target = resolve(storageKey);
        Files.createDirectories(target.getParent());
        Path temp = tempPathFor(target);
        try {
            Files.copy(data, temp, StandardCopyOption.REPLACE_EXISTING);
            moveIntoPlace(temp, target);
            return storageKey;
        } catch (IOException | RuntimeException e) {
            // 正式路径此时尚未出现（或仍是旧内容），只需清理临时文件
            deleteQuietly(temp);
            throw e;
        }
    }

    @Override
    public InputStream open(String storageKey) throws IOException {
        Path file = resolve(storageKey);
        if (!Files.exists(file)) {
            throw new java.io.FileNotFoundException("存储键不存在：" + storageKey);
        }
        return Files.newInputStream(file);
    }

    @Override
    public boolean exists(String storageKey) {
        try {
            return Files.exists(resolve(storageKey));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolve(storageKey));
    }

    @Override
    public long size(String storageKey) throws IOException {
        return Files.size(resolve(storageKey));
    }

    @Override
    public String generateKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 本地磁盘**不提供直链**（M-22 / E-01）：返回空，由上层回退为"后端代理下载地址"。
     * 这样"切换存储后端不改业务代码"——业务侧只问"能不能给个直链"，本地回答"不能"。
     */
    @Override
    public java.util.Optional<String> presignedUrl(String storageKey, String downloadFileName,
                                                   java.time.Duration ttl) {
        return java.util.Optional.empty();
    }

    @Override
    public StorageBackend backend() {
        return StorageBackend.LOCAL;
    }

    /**
     * 生效根路径（全局设置优先、配置兜底，见 {@link StorageRootProvider}）。
     *
     * <p>未配置 → **2003**（存储路径不可用）而非 500：这是可预期的运维配置问题，
     * 必须给出明确业务码与可读原因（R2 + 契约 §十二）。
     */
    private Path rootDir() {
        String root = storageRootProvider.currentRoot();
        if (root == null || root.isBlank()) {
            throw new BusinessException(Code.STORAGE_UNAVAILABLE,
                    "存储根路径未配置：请在全局设置中指定，或配置 toolbox.storage.root");
        }
        return Paths.get(root);
    }

    /**
     * 同目录临时路径。
     *
     * <p>⚠️ **刻意不走** {@link #KEY_PATTERN} 校验：存储键允许用满 64 字符，加后缀会超限而被误判非法。
     * 安全性由 {@link #resolve} 保证 —— 目标路径已做过归一化与"是否仍在根目录内"校验，
     * 其**兄弟路径**必然同样位于根目录内。
     */
    private Path tempPathFor(Path target) {
        return target.resolveSibling(target.getFileName().toString() + TEMP_SUFFIX);
    }

    /**
     * 原子改名就位；文件系统不支持原子移动时回退普通替换。
     *
     * <p>回退分支为**防御性**代码（本地磁盘同目录改名通常都支持原子移动），单测无法稳定构造，
     * 故列为代码审查项（见 `docs/acceptance/B5-后端下载与合并稳健性.md` A3）。
     */
    private void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 尽力清理临时文件：清理失败**不得掩盖**原始异常（错误原因优先） */
    private void deleteQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // 正式路径仍未出现，无"半截文件"风险；残留临时文件由 N-09 清理任务兜底
        }
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || !KEY_PATTERN.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("非法的存储键：" + storageKey);
        }
        Path root = rootDir().normalize();
        Path file = root.resolve(storageKey).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("存储键越界：" + storageKey);
        }
        return file;
    }
}
