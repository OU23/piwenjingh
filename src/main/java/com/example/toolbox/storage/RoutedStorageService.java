package com.example.toolbox.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * 存储路由实现（M-22，{@code @Primary}）—— 业务方注入的 {@link StorageService} 即为本类。
 *
 * <p><b>为什么需要它</b>：{@code storage_backend} 是**逐文件**的，而全局只有一个"写入开关"
 * （{@code toolbox.storage.type}）。混合态（部分本地、部分已迁 OSS）下，若只装配单一实现，
 * 会出现"读不到 / 发不出签名 URL"—— 故两个实现**同时存在**，由本类按**文件实际落点**路由。
 *
 * <p><b>路由规则</b>：
 * <ul>
 *   <li><b>写</b>（{@code save}）→ 按全局开关 {@code type} 指向的后端；</li>
 *   <li><b>读 / 探测 / 删</b>（{@code open}/{@code exists}/{@code size}/{@code delete}）→
 *       按该存储键在 {@link StorageBackendLocator} 里的落点，**只作用于那一个后端**；</li>
 *   <li>落点查不到（如文件已上传但版本尚未落库）→ 按写入后端兜底；</li>
 *   <li>{@code presignedUrl} → 随落点走（本地实现返回空，OSS 返回签名 URL）。</li>
 * </ul>
 *
 * <p><b>隔离（E-02）</b>：任一后端出错只影响该次操作，不会波及其他接口；
 * 本类不吞异常、不做跨后端连带操作（**绝不"两个都删"**）。
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class RoutedStorageService implements StorageService {

    private final LocalStorageService localStorageService;
    private final OssStorageService ossStorageService;
    private final StorageProperties storageProperties;
    private final StorageBackendLocator backendLocator;

    /** 写入目标：由全局开关 {@code toolbox.storage.type} 决定（只影响新文件） */
    private StorageService writeTarget() {
        return storageProperties.writeBackend() == StorageBackend.OSS ? ossStorageService : localStorageService;
    }

    /** 读取 / 删除目标：由该键的实际落点决定（查不到则按写入后端兜底） */
    private StorageService targetFor(String storageKey) {
        StorageBackend backend = backendLocator.backendOf(storageKey)
                .orElseGet(storageProperties::writeBackend);
        return backend == StorageBackend.OSS ? ossStorageService : localStorageService;
    }

    @Override
    public String save(String storageKey, InputStream data, long size) throws IOException {
        return writeTarget().save(storageKey, data, size);
    }

    @Override
    public InputStream open(String storageKey) throws IOException {
        return targetFor(storageKey).open(storageKey);
    }

    @Override
    public boolean exists(String storageKey) {
        return targetFor(storageKey).exists(storageKey);
    }

    @Override
    public void delete(String storageKey) throws IOException {
        // 只删"该文件所在的那一个后端"；不做跨后端连带删除
        targetFor(storageKey).delete(storageKey);
    }

    @Override
    public long size(String storageKey) throws IOException {
        return targetFor(storageKey).size(storageKey);
    }

    @Override
    public String generateKey() {
        return localStorageService.generateKey();
    }

    @Override
    public Optional<String> presignedUrl(String storageKey, String downloadFileName, Duration ttl) {
        return targetFor(storageKey).presignedUrl(storageKey, downloadFileName, ttl);
    }

    @Override
    public StorageBackend backend() {
        return storageProperties.writeBackend();
    }
}
