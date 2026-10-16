package com.example.toolbox.upload.service;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class StsCredentialCacheTest {
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger calls = new AtomicInteger();
    private final Supplier<StsCredential> issuer = () -> new StsCredential(
            "ak-" + calls.incrementAndGet(), "secret", "token", clock.instant().plusSeconds(3600).toString());

    @Test
    void sameSessionAndPolicyReuseCredentialUntilRefreshWindow() {
        var cache = new StsCredentialCache(clock, 10);
        var first = cache.get("upload-1", "dept-1", issuer);
        clock.now = clock.now.plusSeconds(3479);
        assertSame(first, cache.get("upload-1", "dept-1", issuer));
        assertEquals(1, calls.get());
        clock.now = clock.now.plusSeconds(1);
        assertNotSame(first, cache.get("upload-1", "dept-1", issuer));
        assertEquals(2, calls.get());
    }

    @Test
    void sessionAndPolicyBothIsolateCredentials() {
        var cache = new StsCredentialCache(clock, 10);
        var first = cache.get("upload-1", "dept-1", issuer);
        assertNotSame(first, cache.get("upload-2", "dept-1", issuer));
        assertNotSame(first, cache.get("upload-1", "dept-2", issuer));
        var verification = cache.get("verification", "object-1-read", issuer);
        assertNotSame(first, verification);
        assertNotSame(verification, cache.get("verification", "object-2-read", issuer));
        assertEquals(5, calls.get());
    }

    @Test
    void refreshFailureDoesNotReturnOldCredentialOrCacheFailure() {
        var cache = new StsCredentialCache(clock, 10);
        var first = cache.get("session", "policy", issuer);
        clock.now = clock.now.plusSeconds(3500);
        assertThrows(BusinessException.class, () -> cache.get("session", "policy", () -> {
            throw new BusinessException(Code.STORAGE_OSS_UNAVAILABLE);
        }));
        assertNotSame(first, cache.get("session", "policy", issuer));
        assertEquals(2, calls.get());
    }

    @Test
    void invalidOrNearlyExpiredCredentialsAreNotCached() {
        var cache = new StsCredentialCache(clock, 10);
        for (String expiration : new String[]{null, "bad-date", clock.now.minusSeconds(1).toString(),
                clock.now.plusSeconds(120).toString()}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> cache.get("session", "policy", () ->
                            new StsCredential("ak", "secret", "token", expiration)));
            assertEquals(Code.STORAGE_OSS_UNAVAILABLE, ex.getCode());
        }
        assertNotNull(cache.get("session", "policy", issuer));
        assertEquals(1, calls.get());
    }

    @Test
    void capacityEvictsLeastRecentlyUsedCredential() {
        var cache = new StsCredentialCache(clock, 2);
        var a = cache.get("session", "a", issuer);
        var b = cache.get("session", "b", issuer);
        assertSame(a, cache.get("session", "a", issuer));
        cache.get("session", "c", issuer);
        assertNotSame(b, cache.get("session", "b", issuer));
        assertEquals(4, calls.get());
    }

    @Test
    void concurrentRequestsOnlyIssueOnce() throws Exception {
        var cache = new StsCredentialCache(clock, 10);
        var executor = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<StsCredential>>();
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return cache.get("session", "policy", issuer);
                }));
            }
            start.countDown();
            var first = results.get(0).get(5, TimeUnit.SECONDS);
            for (var result : results) {
                assertSame(first, result.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, calls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-16T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
