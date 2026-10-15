package com.example.toolbox.setting.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.constant.SettingKey;
import com.example.toolbox.common.util.PathUtils;
import com.example.toolbox.setting.entity.SettingDO;
import com.example.toolbox.setting.mapper.SettingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 全局设置服务：读写键值设置（storageRoot / maxFileSize）。
 * 本阶段仅供初始化写入默认值；B4 在此之上补 GET / PUT 接口与路径可写校验。
 */
@Service
@RequiredArgsConstructor
public class SettingService {

    /** 单文件上限默认值：10 GB（契约 §13.5） */
    public static final long DEFAULT_MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024;

    /** 分片大小默认值：20 MB（M-23 / B14） */
    public static final long DEFAULT_CHUNK_SIZE = 20L * 1024 * 1024;
    /** 分片大小下限：5 MB */
    public static final long MIN_CHUNK_SIZE = 5L * 1024 * 1024;
    /** 分片大小上限：100 MB */
    public static final long MAX_CHUNK_SIZE = 100L * 1024 * 1024;

    private final SettingMapper settingMapper;

    /** 读取设置值；不存在返回 null */
    public String getValue(String key) {
        SettingDO s = settingMapper.selectOne(
                Wrappers.<SettingDO>lambdaQuery().eq(SettingDO::getSettingKey, key));
        return s == null ? null : s.getSettingValue();
    }

    /**
     * 存储根路径（**读取入口**，契约 §十）。
     *
     * <p>返回归一化后的正斜杠路径（v1.5 路径约定），供存储层（{@code StorageRootProvider}）与
     * 设置读取接口共用；未配置返回 null。
     */
    public String getStorageRoot() {
        return PathUtils.normalizeSlashes(getValue(SettingKey.STORAGE_ROOT));
    }

    /**
     * 单文件大小上限（字节）—— **大小上限的唯一读取入口**（principles.md R3）。
     *
     * <p>合并校验（B3）与设置读取（B4）共用本方法，禁止各自解析配置值。
     * 未配置或值非法时回落到默认 10 GB，保证上传链路不因设置缺失而不可用。
     */
    public long getMaxFileSize() {
        String raw = getValue(SettingKey.MAX_FILE_SIZE);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_FILE_SIZE;
        }
        try {
            long v = Long.parseLong(raw.trim());
            return v > 0 ? v : DEFAULT_MAX_FILE_SIZE;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_FILE_SIZE;
        }
    }

    /**
     * 上传分片大小（字节）—— **分片大小的唯一读取入口**（M-23 / B14）。
     *
     * <p>`local` 模式：前端据此切片；`oss` 直传模式：即 OSS part 大小（并经 `credentials` 回显）。
     * 未配置 / 值非法 / 越界（不在 {@link #MIN_CHUNK_SIZE} ~ {@link #MAX_CHUNK_SIZE}）一律回落
     * 默认 20 MB —— 与 {@link #getMaxFileSize()} 同款容错，保证上传链路不因设置缺失或写坏而不可用。
     */
    public long getChunkSize() {
        String raw = getValue(SettingKey.CHUNK_SIZE);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CHUNK_SIZE;
        }
        try {
            long v = Long.parseLong(raw.trim());
            return (v >= MIN_CHUNK_SIZE && v <= MAX_CHUNK_SIZE) ? v : DEFAULT_CHUNK_SIZE;
        } catch (NumberFormatException e) {
            return DEFAULT_CHUNK_SIZE;
        }
    }

    /** 写入（不存在则插入，存在则更新），幂等 */
    @Transactional
    public void putValue(String key, String value) {
        upsert(key, value);
    }

    /** 幂等初始化默认设置（供系统初始化调用） */
    @Transactional
    public void initDefaults(String storageRoot) {
        upsert(SettingKey.STORAGE_ROOT, storageRoot);
        upsert(SettingKey.MAX_FILE_SIZE, String.valueOf(DEFAULT_MAX_FILE_SIZE));
        upsert(SettingKey.CHUNK_SIZE, String.valueOf(DEFAULT_CHUNK_SIZE));
    }

    private void upsert(String key, String value) {
        SettingDO existing = settingMapper.selectOne(
                Wrappers.<SettingDO>lambdaQuery().eq(SettingDO::getSettingKey, key));
        if (existing == null) {
            SettingDO s = new SettingDO();
            s.setSettingKey(key);
            s.setSettingValue(value);
            s.setUpdateTime(LocalDateTime.now());
            settingMapper.insert(s);
        } else {
            existing.setSettingValue(value);
            existing.setUpdateTime(LocalDateTime.now());
            settingMapper.updateById(existing);
        }
    }
}
