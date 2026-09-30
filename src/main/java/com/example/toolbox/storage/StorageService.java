package com.example.toolbox.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件存储适配层接口（principles.md R1）。
 * 业务层只持有存储键，物理路径禁止出现在数据库、接口、日志与前端。
 * 具体实现可为本地磁盘（{@link LocalStorageService}）或对象存储（{@link OssStorageService}），业务代码不感知（E-01）。
 *
 * <p><b>落点路由（M-22）</b>：业务方注入的是 {@link RoutedStorageService}（{@code @Primary}），
 * 它按"存储键的落点"把请求转给对应实现 —— 业务代码无需感知文件在后端哪里，
 * 且**读写 / 删除只作用于文件实际所在的那一个后端**（不做跨后端连带操作）。
 */
public interface StorageService {

    /**
     * 保存文件流，返回存储键。
     *
     * <p><b>落盘语义（B5 起）：原子落盘</b> —— 实现必须"先写同目录临时文件，再原子改名就位"，
     * 使<b>正式路径只会在内容完整之后出现</b>；写入失败或进程中途被杀时<b>不得在正式路径留下半截文件</b>，
     * 且必须<b>清理临时文件</b>。理由：合并（分片合并 → 落盘）是长耗时流式写入，若直接写正式路径，
     * 一旦中断，库中的版本记录会指向一个残缺文件 —— 即"DB 有记录、磁盘文件不完整"的漂移数据，
     * 而该数据还会被当作可下载资源对外通告。
     *
     * @param storageKey 存储键（由调用方提供的非敏感标识，如前端生成的 fileKey）
     * @param data       文件输入流（调用方负责关闭）
     * @param size       大小（字节），用于校验/统计（本地实现 -1 表示由流长度决定；**对象存储实现要求已知长度**）
     * @return 实际使用的存储键
     */
    String save(String storageKey, InputStream data, long size) throws IOException;

    /**
     * 打开文件流用于读取（下载）。调用方负责关闭。
     */
    InputStream open(String storageKey) throws IOException;

    boolean exists(String storageKey);

    void delete(String storageKey) throws IOException;

    long size(String storageKey) throws IOException;

    /**
     * 生成一个系统存储键（当调用方不提供时使用）。
     */
    String generateKey();

    /**
     * 生成"可直接访问的短时效地址"（M-22 / 契约 v1.19「受控例外」）。
     *
     * <p><b>受控例外</b>：不得提供永久 / 无鉴权直链；本方法产物为**带过期时间、绑定对象的签名 URL**。
     *
     * @param storageKey       存储键
     * @param downloadFileName 期望的下载文件名（可为 null；OSS 以 {@code response-content-disposition} 覆盖）
     * @param ttl              有效期
     * @return 可访问地址；**本实现无直链能力时返回 {@code Optional.empty()}**（本地磁盘实现），
     *         由上层回退为"后端代理下载地址"
     */
    java.util.Optional<String> presignedUrl(String storageKey, String downloadFileName, java.time.Duration ttl);

    /** 本实现对应的落点（用于路由与断言）。 */
    StorageBackend backend();
}
