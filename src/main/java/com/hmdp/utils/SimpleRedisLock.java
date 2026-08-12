package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis SETNX 和 Lua 脚本实现的简单分布式锁。
 */
public class SimpleRedisLock implements ILock {

    private static final String PROCESS_ID_PREFIX =
            UUID.randomUUID().toString(true) + "-";

    /**
     * 静态脚本对象只在类加载时初始化一次，避免每次释放锁都重新读取脚本资源。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private final String key;
    private final StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(
            String key,
            StringRedisTemplate stringRedisTemplate) {
        this.key = key;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String ownerId = getOwnerId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(
                        key,
                        ownerId,
                        timeoutSec,
                        TimeUnit.SECONDS
                );
        return BooleanUtil.isTrue(success);
    }

    @Override
    public void unlock() {
        /*
         * KEYS[1] 对应这里传入的 key，ARGV[1] 对应 getOwnerId()。
         * Redis 将整个 Lua 脚本作为一个原子操作执行，避免检查后锁过期而误删新锁。
         */
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(key),
                getOwnerId()
        );
    }

    private String getOwnerId() {
        return PROCESS_ID_PREFIX + Thread.currentThread().getId();
    }
}
