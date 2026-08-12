package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private static final String PROCESS_ID_PREFIX=
            UUID.randomUUID().toString(true)+"-";
    private final String key;
    private final StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(
            String key,
            StringRedisTemplate stringRedisTemplate) {
        this.key = key;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(Long timeoutSec) {
        String ownerId=getOwnerId();
        boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(
                        key,
                        ownerId,
                        timeoutSec,
                        TimeUnit.SECONDS
                );
        return BooleanUtil.isTrue(success);
    }

    @Override
    public void unlock(Long timeoutSec) {
    String ownerId=getOwnerId();
    String storeOwnerId=
            stringRedisTemplate.opsForValue().get(key);
    if(ownerId.equals(storeOwnerId)){
        stringRedisTemplate.delete(key);
    }

    }

    private String getOwnerId() {
        return PROCESS_ID_PREFIX + Thread.currentThread().getId();
    }


}
