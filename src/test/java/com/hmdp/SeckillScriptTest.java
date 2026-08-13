package com.hmdp;

import cn.hutool.core.lang.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.Arrays;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 秒杀Lua脚本最小业务路径测试。
 */
@SpringBootTest
class SeckillScriptTest {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void shouldCheckSeckillQualificationAtomically() {
        String testId = UUID.randomUUID().toString(true);
        String stockKey = SECKILL_STOCK_KEY + "test:" + testId;
        String orderKey = SECKILL_ORDER_KEY + "test:" + testId;

        try {
            stringRedisTemplate.opsForValue().set(
                    stockKey,
                    "2",
                    Duration.ofMinutes(5)
            );

            /*
             * 两份库存可以同时覆盖一人一单和库存不足：
             * 用户1成功后重复请求被拦截，用户2用完最后一份，用户3再请求时售罄。
             */
            assertEquals(0L, execute(stockKey, orderKey, "user-1"));
            assertEquals(2L, execute(stockKey, orderKey, "user-1"));
            assertEquals(0L, execute(stockKey, orderKey, "user-2"));
            assertEquals(1L, execute(stockKey, orderKey, "user-3"));
            assertEquals("0", stringRedisTemplate.opsForValue().get(stockKey));

            // 资格Set应继承库存key的有效期，避免活动结束后形成永久数据。
            Long orderTtl = stringRedisTemplate.getExpire(orderKey);
            assertTrue(orderTtl != null && orderTtl > 0);

            stringRedisTemplate.delete(stockKey);
            assertEquals(3L, execute(stockKey, orderKey, "user-4"));
        } finally {
            stringRedisTemplate.delete(Arrays.asList(stockKey, orderKey));
        }
    }

    private Long execute(String stockKey, String orderKey, String userId) {
        return stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(stockKey, orderKey),
                userId
        );
    }
}
