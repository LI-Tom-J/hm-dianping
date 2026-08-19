package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class HmDianPingApplicationTests {

    private static final String HYPER_LOG_LOG_TEST_KEY = "test:hll:uv";
    private static final int UV_TOTAL = 1_000_000;
    private static final int UV_BATCH_SIZE = 1_000;
    private static final double MAX_ACCEPTABLE_ERROR_RATE = 0.02D;

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void loadShopData() {
        List<Shop> shopList = shopService.list();

        Map<Long, List<Shop>> shopByType = shopList.stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));

        for (Map.Entry<Long, List<Shop>> entry : shopByType.entrySet()) {

            Long typeId = entry.getKey();
            List<Shop> shops = entry.getValue();

            String key = SHOP_GEO_KEY + typeId;
            List<RedisGeoCommands.GeoLocation<String>> locations =
                    new ArrayList<>(shops.size());
            for (Shop shop : shops) {
                Point point = new Point(shop.getX(), shop.getY());

                locations.add(
                        new RedisGeoCommands.GeoLocation<>(
                                shop.getId().toString(),
                                point
                        )
                );
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    void testHyperLogLogBasics() {
        // 使用独立测试Key，避免影响正式业务数据
        stringRedisTemplate.delete(HYPER_LOG_LOG_TEST_KEY);

        try {
            // user:1重复出现，但UV统计中只应该计算一次
            stringRedisTemplate.opsForHyperLogLog().add(
                    HYPER_LOG_LOG_TEST_KEY,
                    "user:1",
                    "user:2",
                    "user:1"
            );

            Long uv = stringRedisTemplate.opsForHyperLogLog()
                    .size(HYPER_LOG_LOG_TEST_KEY);

            System.out.println("UV统计结果：" + uv);

            // 两个不同用户，因此期望UV为2
            assertEquals(Long.valueOf(2L), uv);
        } finally {
            // 即使断言失败，也删除测试Key，避免留下实验数据
            stringRedisTemplate.delete(HYPER_LOG_LOG_TEST_KEY);
        }
    }

    @Test
    void testHyperLogLogMillionUsers() {
        // 清除上次运行可能留下的数据，保证每次测试都从空集合开始
        stringRedisTemplate.delete(HYPER_LOG_LOG_TEST_KEY);

        String[] userBatch = new String[UV_BATCH_SIZE];

        try {
            // 1. 模拟100万个不同用户
            for (int i = 1; i <= UV_TOTAL; i++) {
                int batchIndex = (i - 1) % UV_BATCH_SIZE;
                userBatch[batchIndex] = "user:" + i;

                // 每收集1000个用户批量发送一次，减少Java与Redis的网络交互
                if (batchIndex == UV_BATCH_SIZE - 1) {
                    stringRedisTemplate.opsForHyperLogLog().add(
                            HYPER_LOG_LOG_TEST_KEY,
                            userBatch
                    );
                }
            }

            // 2. 获取HyperLogLog估算的独立用户数量
            Long estimatedUv = stringRedisTemplate
                    .opsForHyperLogLog()
                    .size(HYPER_LOG_LOG_TEST_KEY);

            // 3. 计算估算值与真实值之间的误差
            long absoluteError = Math.abs(estimatedUv - UV_TOTAL);
            double errorRate = absoluteError / (double) UV_TOTAL;

            System.out.println("真实UV：" + UV_TOTAL);
            System.out.println("估算UV：" + estimatedUv);
            System.out.println("绝对误差：" + absoluteError);
            System.out.printf(
                    "误差率：%.4f%%%n",
                    errorRate * 100
            );

            // HyperLogLog允许少量误差，这里把测试容忍范围设置为2%
            assertTrue(
                    errorRate <= MAX_ACCEPTABLE_ERROR_RATE,
                    "HyperLogLog误差率超过预期范围"
            );
        } finally {
            // 实验结束后清理Redis，避免测试数据长期存在
            stringRedisTemplate.delete(HYPER_LOG_LOG_TEST_KEY);
        }
    }

}
