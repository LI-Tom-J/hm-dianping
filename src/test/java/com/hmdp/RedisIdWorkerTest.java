package com.hmdp;

import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RedisIdWorkerTest {

    private static final int TASK_COUNT = 100;
    private static final int IDS_PER_TASK = 100;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    void shouldGenerateUniqueIdsConcurrently() throws InterruptedException {
        // 使用线程池模拟多个请求同时生成订单ID
        ExecutorService executorService =
                Executors.newFixedThreadPool(20);

        // 并发Set既能保证线程安全，也能直接验证是否出现重复ID
        Set<Long> generatedIds = ConcurrentHashMap.newKeySet();

        CountDownLatch countDownLatch =
                new CountDownLatch(TASK_COUNT);

        for (int taskIndex = 0; taskIndex < TASK_COUNT; taskIndex++) {
            executorService.submit(() -> {
                try {
                    for (int idIndex = 0;
                         idIndex < IDS_PER_TASK;
                         idIndex++) {

                        long id = redisIdWorker.nextId("order");
                        generatedIds.add(id);
                    }
                } finally {
                    // 无论任务是否异常都必须减一，否则主测试线程可能一直等待
                    countDownLatch.countDown();
                }
            });
        }

        // 最多等待30秒，避免Redis异常时测试永久阻塞
        boolean completed =
                countDownLatch.await(30, TimeUnit.SECONDS);

        executorService.shutdown();

        assertTrue(completed, "ID生成任务未在规定时间内完成");

        // 100个任务，每个生成100个ID，最终应该正好有10000个不同ID
        assertEquals(
                TASK_COUNT * IDS_PER_TASK,
                generatedIds.size()
        );
    }
}