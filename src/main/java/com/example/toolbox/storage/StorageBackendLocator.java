package com.example.toolbox.storage;

import java.util.Optional;

/**
 * 存储键 → 落点 查询（M-22）。依赖方向：**业务模块实现本接口，存储层依赖本接口** ——
 * 与 {@link StorageRootProvider} 同款套路，避免存储层反向依赖业务表。
 *
 * <p><b>为什么需要它</b>：混合态下（部分文件在本地、部分已迁 OSS），
 * 读写 / 删除**必须按每个文件的实际落点**选择后端；而 {@link StorageService} 的方法只带存储键，
 * 故由本接口按存储键反查落点（`tb_tool_version.file_key` 有唯一索引，一次索引查询）。
 *
 * <p>查询不到（如文件已上传但版本记录尚未落库、或记录已随工具彻底删除）返回
 * {@link Optional#empty()}，由上层按"写入落点"兜底。
 */
public interface StorageBackendLocator {

    /** 该存储键当前所在的落点；查不到返回 {@code Optional.empty()} */
    Optional<StorageBackend> backendOf(String storageKey);
}
