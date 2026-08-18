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

@SpringBootTest
class HmDianPingApplicationTests {

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

}
