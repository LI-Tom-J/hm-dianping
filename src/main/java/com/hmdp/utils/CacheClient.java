package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
public class CacheClient {

    private static final int CACHE_REBUILD_POOL_SIZE = 10;
    private static final long CACHE_RETRY_INTERVAL_MILLIS = 50L;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(CACHE_REBUILD_POOL_SIZE);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        R result = dbFallback.apply(id);
        if (result == null) {
            stringRedisTemplate.opsForValue().set(
                    key,
                    " ",
                    RedisConstants.CACHE_NULL_TTL,
                    TimeUnit.MINUTES);
            return null;
        }
        set(key, result, time, unit);
        return result;
    }

    public <R, ID> R queryWithMutex(
            String keyPrefix,
            String lockPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        String lockKey = lockPrefix + id;
        boolean isLock = false;
        try {
            isLock = tryLock(lockKey);
            if (!isLock) {
                Thread.sleep(CACHE_RETRY_INTERVAL_MILLIS);
                return queryWithMutex(keyPrefix, lockPrefix, id, type, dbFallback, time, unit);
            }

            return queryWithPassThrough(keyPrefix, id, type, dbFallback, time, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            if (isLock) {
                unlock(lockKey);
            }
        }
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix,
            String lockPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            return queryMissingLogicalCache(keyPrefix, lockPrefix, id, type, dbFallback, time, unit);
        }
        if (StrUtil.isBlank(json)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime == null) {
            R result = JSONUtil.toBean(json, type);
            setWithLogicalExpire(key, result, time, unit);
            return result;
        }
        if (redisData.getData() == null) {
            return null;
        }

        R result = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
        if (expireTime.isAfter(LocalDateTime.now())) {
            return result;
        }

        String lockKey = lockPrefix + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            // 逻辑过期时先返回旧数据，重建交给拿到锁的后台线程，避免热点 key 同时回源。
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R freshResult = dbFallback.apply(id);
                    if (freshResult == null) {
                        stringRedisTemplate.opsForValue().set(
                                key,
                                " ",
                                RedisConstants.CACHE_NULL_TTL,
                                TimeUnit.MINUTES);
                        return;
                    }
                    setWithLogicalExpire(key, freshResult, time, unit);
                } finally {
                    unlock(lockKey);
                }
            });
        }

        return result;
    }

    private <R, ID> R queryMissingLogicalCache(
            String keyPrefix,
            String lockPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        String key = keyPrefix + id;
        String lockKey = lockPrefix + id;
        boolean isLock = false;
        try {
            isLock = tryLock(lockKey);
            if (!isLock) {
                Thread.sleep(CACHE_RETRY_INTERVAL_MILLIS);
                return queryWithLogicalExpire(keyPrefix, lockPrefix, id, type, dbFallback, time, unit);
            }

            String json = stringRedisTemplate.opsForValue().get(key);
            if (json != null) {
                return queryWithLogicalExpire(keyPrefix, lockPrefix, id, type, dbFallback, time, unit);
            }

            R result = dbFallback.apply(id);
            if (result == null) {
                stringRedisTemplate.opsForValue().set(
                        key,
                        " ",
                        RedisConstants.CACHE_NULL_TTL,
                        TimeUnit.MINUTES);
                return null;
            }
            setWithLogicalExpire(key, result, time, unit);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            if (isLock) {
                unlock(lockKey);
            }
        }
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
