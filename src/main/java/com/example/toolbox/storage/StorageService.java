package com.example.toolbox.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件存储适配层接口（principles.md R1）。
 * 业务层只持有存储键，物理路径禁止出现在数据库、接口、日志与前端。
 * 具体实现可为本地磁盘或对象存储，业务代码不感知。
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
     * @param size       大小（字节），用于校验/统计（-1 表示由流长度决定）
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
}
