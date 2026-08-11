package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static com.hmdp.utils.RedisConstants.ID_INCREMENT_KEY_PREFIX;

/**
 * 基于时间戳和Redis自增序列生成全局唯一ID
 */
@Component
public class RedisIdWorker {

    /**
     * 自定义起始时间：2022-01-01 00:00:00 UTC
     *
     * 使用相对时间戳可以减少时间部分占用的二进制位数。
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /**
     * 序列号占用低32位，时间戳放在高位。
     */
    private static final int SEQUENCE_BITS = 32;

    /**
     * 每天使用不同的Redis计数器，避免单个计数器持续增长。
     */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy:MM:dd");

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成指定业务的全局唯一ID。
     *
     * @param keyPrefix 业务前缀，例如 order
     * @return 全局唯一ID
     */
    public long nextId(String keyPrefix){
        // 1. 使用相对时间戳作为高位，使ID整体保持随时间递增
        long currentTimestamp = Instant.now().getEpochSecond();
        long timestamp=currentTimestamp-BEGIN_TIMESTAMP;

        String currentDate= LocalDateTime.now().format(DATE_FORMATTER);
        String redisKey=
                ID_INCREMENT_KEY_PREFIX + keyPrefix + ":" + currentDate;

        // 3. Redis自增为同一秒内产生的ID提供唯一序列号
        Long sequence = stringRedisTemplate
                .opsForValue()
                .increment(redisKey);

        if (sequence == null) {
            throw new IllegalStateException("Redis生成ID序列失败");
        }

        // 4. 时间戳放入高32位，Redis序列号放入低32位
        return timestamp << SEQUENCE_BITS | sequence;
    }

}
