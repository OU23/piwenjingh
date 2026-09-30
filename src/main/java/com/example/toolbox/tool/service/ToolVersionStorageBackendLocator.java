package com.example.toolbox.tool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.toolbox.storage.StorageBackend;
import com.example.toolbox.storage.StorageBackendLocator;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.mapper.ToolVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 落点查询实现（M-22）：按存储键反查 {@code tb_tool_version.storage_backend}。
 *
 * <p>依赖方向：**业务模块（tool）实现 storage 包定义的 {@link StorageBackendLocator}**，
 * 存储层只依赖接口 —— 与 {@code StorageRootProvider} 同款，避免存储层反向依赖业务表（R1）。
 *
 * <p>{@code file_key} 有唯一索引，本查询为一次索引命中；仅在读 / 删路径调用（稳态下写入路径不查）。
 */
@Component
@RequiredArgsConstructor
public class ToolVersionStorageBackendLocator implements StorageBackendLocator {

    private final ToolVersionMapper toolVersionMapper;

    @Override
    public Optional<StorageBackend> backendOf(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return Optional.empty();
        }
        ToolVersionDO version = toolVersionMapper.selectOne(
                new LambdaQueryWrapper<ToolVersionDO>()
                        .select(ToolVersionDO::getStorageBackend)
                        .eq(ToolVersionDO::getFileKey, storageKey)
                        .last("LIMIT 1"));
        if (version == null) {
            return Optional.empty();
        }
        return Optional.of(StorageBackend.of(version.getStorageBackend()));
    }
}
