package com.example.toolbox.upload.service;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;

/** 仅在内存保存凭据；会话身份和权限策略相同才允许复用。 */
final class StsCredentialCache {
    private static final Duration REFRESH_AHEAD = Duration.ofMinutes(2);
    private final Clock clock;
    private final Cache<Key, Entry> entries;

    StsCredentialCache(Clock clock, int capacity) {
        this.clock = clock;
        this.entries = CacheBuilder.newBuilder().maximumSize(capacity).build();
    }

    /** Guava 原子更新同一缓存键，避免并发重复签发；到期依据 STS 返回的实际时间。 */
    StsCredential get(String sessionName, String policy, Supplier<StsCredential> issuer) {
        return entries.asMap().compute(new Key(sessionName, policy), (key, cached) ->
                cached != null && cached.refreshAt().isAfter(clock.instant())
                        ? cached : load(issuer)).credential();
    }

    private Entry load(Supplier<StsCredential> issuer) {
        StsCredential credential = issuer.get();
        Instant refreshAt;
        try {
            refreshAt = Instant.parse(credential.expiration()).minus(REFRESH_AHEAD);
        } catch (DateTimeParseException | NullPointerException e) {
            throw unavailable();
        }
        if (!refreshAt.isAfter(clock.instant())) {
            throw unavailable();
        }
        return new Entry(credential, refreshAt);
    }

    private BusinessException unavailable() {
        return new BusinessException(Code.STORAGE_OSS_UNAVAILABLE, "对象存储暂不可用");
    }

    private record Key(String sessionName, String policy) {}
    private record Entry(StsCredential credential, Instant refreshAt) {}
}
