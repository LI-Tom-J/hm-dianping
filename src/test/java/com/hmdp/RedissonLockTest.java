package com.hmdp;

import cn.hutool.core.lang.UUID;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOCK_ORDER_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redisson锁行为测试。
 *
 * 使用独立且执行后清理的测试key，避免影响真实秒杀订单锁。
 */
@SpringBootTest
class RedissonLockTest {

    private static final int WATCH_DOG_OBSERVE_SECONDS = 35;
    private static final int EXPLICIT_LEASE_SECONDS = 3;
    private static final int EXPLICIT_LEASE_OBSERVE_SECONDS = 4;

    @Resource
    private RedissonClient redissonClient;

    @Test
    void shouldSupportReentrantLockInSameThread() {
        String lockKey = createTestLockKey();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 同一线程重复获取同一把锁，用于验证Redisson的可重入能力。
            assertTrue(lock.tryLock());
            assertTrue(lock.tryLock());
            assertEquals(2, lock.getHoldCount());
        } finally {
            // 可重入几次就必须释放几次，否则锁的重入计数无法归零。
            while (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Test
    void shouldRejectAnotherThreadWhileLockIsHeld() throws Exception {
        String lockKey = createTestLockKey();
        RLock ownerLock = redissonClient.getLock(lockKey);
        ExecutorService executorService = Executors.newSingleThreadExecutor();

        try {
            assertTrue(ownerLock.tryLock());

            Future<Boolean> competingResult = executorService.submit(() -> {
                RLock competingLock = redissonClient.getLock(lockKey);
                boolean acquired = competingLock.tryLock();

                if (acquired) {
                    competingLock.unlock();
                }
                return acquired;
            });

            // 持锁线程未释放前，其他线程不能同时进入同一用户的下单临界区。
            assertFalse(competingResult.get(5, TimeUnit.SECONDS));
        } finally {
            if (ownerLock.isHeldByCurrentThread()) {
                ownerLock.unlock();
            }
            executorService.shutdownNow();
        }
    }

    @Test
    void shouldRenewLockWithWatchDog() throws InterruptedException {
        String lockKey = createTestLockKey();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            /*
             * 未指定固定租约时间时，Redisson会启用WatchDog自动续期，
             * 防止业务仍在执行时锁因默认超时时间到达而提前释放。
             */
            lock.lock();

            long initialTtl = lock.remainTimeToLive();
            assertTrue(initialTtl > 0);

            // 等待超过默认30秒锁超时时间，验证WatchDog续期后锁仍归当前线程持有。
            TimeUnit.SECONDS.sleep(WATCH_DOG_OBSERVE_SECONDS);

            assertTrue(lock.isLocked());
            assertTrue(lock.isHeldByCurrentThread());
            assertTrue(lock.remainTimeToLive() > 0);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Test
    void shouldExpireAfterExplicitLeaseTime() throws InterruptedException {
        String lockKey = createTestLockKey();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            /*
             * 显式指定租约时间后不会使用WatchDog续期；即使业务仍未结束，
             * Redis中的锁也会在租约到期后自动释放。
             */
            boolean acquired = lock.tryLock(
                    0,
                    EXPLICIT_LEASE_SECONDS,
                    TimeUnit.SECONDS
            );

            assertTrue(acquired);
            assertTrue(lock.isHeldByCurrentThread());

            TimeUnit.SECONDS.sleep(EXPLICIT_LEASE_OBSERVE_SECONDS);

            assertFalse(lock.isLocked());
            assertFalse(lock.isHeldByCurrentThread());
        } finally {
            // 租约可能已经到期，释放前必须确认当前线程仍然持有锁。
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String createTestLockKey() {
        return LOCK_ORDER_KEY + "test:" + UUID.randomUUID().toString(true);
    }
}
