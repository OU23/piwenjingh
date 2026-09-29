package com.example.toolbox.upload.service;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.config.UploadProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 分片临时存储（R1/R4：业务层只与存储键/目录打交道，不拼物理路径）。
 *
 * <p>目录结构：{@code <chunkDir>/<safeIdentifier>/<n>.part}
 *
 * <p><b>identifier 处理口径（契约 §8.3 + N-05）</b>：
 * 它是前端计算的**不透明字符串**，后端**不得校验其格式**（不是 MD5/SHA，长度与字符集不固定）。
 * 但由于要用作目录名，**必须做路径安全校验**，禁止 {@code ..}、{@code /}、{@code \}、
 * 绝对路径、空字节等越界构造 —— 采用"白名单字符过滤 + 归一化后前缀校验"双重防线。
 *
 * <p><b>并发与乱序（契约 §8.4 + N-06）</b>：前端最多 3 个分片并发提交同一 identifier，
 * 且不保证按序号到达。因此分片以"独立文件"落盘（文件名即序号），
 * 天然支持并发写入与乱序到达，不依赖任何顺序假设。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkStorage {

    /** identifier 允许的最大长度（契约 N-05：建议 varchar(128)） */
    private static final int MAX_IDENTIFIER_LENGTH = 128;

    /** 白名单：字母 / 数字 / 连字符 / 下划线 / 点（N-05：字符集 [A-Za-z0-9-]，此处放宽到常用安全字符） */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]+");

    private final UploadProperties uploadProperties;

    /**
     * 校验并返回 identifier 对应的分片目录。
     * 拒绝路径穿越等越界构造 —— 非法即 1003（参数校验失败），不是 500。
     */
    public Path dirFor(String identifier) {
        String safe = sanitize(identifier);
        Path root = chunkRoot();
        Path dir = root.resolve(safe).normalize();
        // 双重防线：归一化后必须仍在根目录之下
        if (!dir.startsWith(root)) {
            throw new BusinessException(Code.PARAM_INVALID, "非法的文件标识");
        }
        return dir;
    }

    /**
     * identifier 安全校验。
     *
     * <p>注意：这里**不校验业务格式**（不要求是哈希），只做安全过滤 —— 符合 N-05
     * "按不透明字符串处理" 的要求。
     */
    private String sanitize(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new BusinessException(Code.PARAM_INVALID, "文件标识不能为空");
        }
        if (identifier.length() > MAX_IDENTIFIER_LENGTH) {
            throw new BusinessException(Code.PARAM_INVALID,
                    "文件标识长度超出上限（" + MAX_IDENTIFIER_LENGTH + "）");
        }
        // 显式拒绝穿越构造，给出明确原因（比单纯白名单不匹配更好定位）
        if (identifier.contains("..") || identifier.contains("/") || identifier.contains("\\")
                || identifier.contains("\0")) {
            throw new BusinessException(Code.PARAM_INVALID, "文件标识包含非法字符");
        }
        if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new BusinessException(Code.PARAM_INVALID, "文件标识包含非法字符");
        }
        return identifier;
    }

    /** 保存单个分片（流式落盘，不整片入内存） */
    public void saveChunk(String identifier, int chunkNumber, InputStream data) {
        Path dir = dirFor(identifier);
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(chunkFileName(chunkNumber));
            // REPLACE_EXISTING：同一分片重传（前端重试）应覆盖而非报错
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException(Code.CHUNK_INVALID, "分片写入失败：" + e.getMessage());
        }
    }

    /** 已成功接收的分片序号（**从 1 开始**，升序返回；无则空列表） */
    public List<Integer> uploadedChunks(String identifier) {
        Path dir = dirFor(identifier);
        List<Integer> nums = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return nums;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.part")) {
            for (Path p : stream) {
                Integer n = parseChunkNumber(p.getFileName().toString());
                if (n != null) {
                    nums.add(n);
                }
            }
        } catch (IOException e) {
            throw new BusinessException(Code.CHUNK_INVALID, "读取已传分片失败：" + e.getMessage());
        }
        // 目录遍历顺序不保证，必须显式排序（契约要求升序返回）
        nums.sort(Comparator.naturalOrder());
        return nums;
    }

    /**
     * 按序流式合并全部分片到输出流（R4：**禁止**把整文件或全部分片读入内存）。
     *
     * <p>逐片打开输入流 → 复制到输出流 → 立即关闭，内存占用与文件大小无关，
     * 仅与缓冲区大小（8 KB）相关。
     *
     * @param totalChunks 分片总数（决定按序读取的边界）
     * @return 实际写入的字节数
     */
    public long mergeTo(String identifier, int totalChunks, OutputStream out) {
        Path dir = dirFor(identifier);
        long written = 0;
        byte[] buffer = new byte[8192];
        for (int i = 1; i <= totalChunks; i++) {
            Path chunk = dir.resolve(chunkFileName(i));
            if (!Files.isRegularFile(chunk)) {
                throw new BusinessException(Code.CHUNK_INVALID,
                        "分片不完整：缺少第 " + i + " 个分片");
            }
            try (InputStream in = Files.newInputStream(chunk)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    written += read;
                }
            } catch (IOException e) {
                throw new BusinessException(Code.CHUNK_INVALID,
                        "合并时读取第 " + i + " 个分片失败：" + e.getMessage());
            }
        }
        return written;
    }

    /** 清理该 identifier 下的全部分片；目录不存在或已清理不报错（幂等） */
    public void clean(String identifier) {
        Path dir = dirFor(identifier);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 单个文件删除失败不阻断整体清理；由定时任务兜底
                        }
                    });
        } catch (IOException e) {
            log.warn("[CLEAN] 分片目录清理失败 identifier={} err={}", identifier, e.getMessage());
        }
    }

    /** 分片根目录（规范化，供安全前缀校验与定时清理使用） */
    public Path chunkRoot() {
        String dir = uploadProperties.getChunkDir();
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("分片临时目录未配置（toolbox.upload.chunk-dir）");
        }
        return Paths.get(dir).toAbsolutePath().normalize();
    }

    /** 分片文件名 = 序号 + .part（序号从 1 开始） */
    private String chunkFileName(int chunkNumber) {
        return chunkNumber + ".part";
    }

    private Integer parseChunkNumber(String fileName) {
        if (!fileName.endsWith(".part")) {
            return null;
        }
        try {
            return Integer.valueOf(fileName.substring(0, fileName.length() - ".part".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
