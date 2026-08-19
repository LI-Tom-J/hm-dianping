package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id) {
        return cacheClient.queryWithMutex(
                RedisConstants.CACHE_SHOP_KEY,
                RedisConstants.LOCK_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.MINUTES);
    }

    public Shop queryWithPassThrough(Long id) {
        return cacheClient.queryWithPassThrough(
                RedisConstants.CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.MINUTES);
    }

    public Shop queryWithLogicalExpire(Long id) {
        return cacheClient.queryWithLogicalExpire(
                RedisConstants.CACHE_SHOP_KEY,
                RedisConstants.LOCK_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.MINUTES);
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);

        cacheClient.delete(RedisConstants.CACHE_SHOP_KEY + id);

        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if(x == null || y == null) {
            Page<Shop> page=lambdaQuery()
                    .eq(Shop::getTypeId,typeId)
                    .page(new Page<>(
                            current,
                            SystemConstants.DEFAULT_PAGE_SIZE
                    ));
            return Result.ok(page.getRecords());
        }

        int from = (current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end=current*SystemConstants.DEFAULT_PAGE_SIZE;

        String key=RedisConstants.SHOP_GEO_KEY+typeId;

        GeoResults<RedisGeoCommands.GeoLocation<String>> results=
                stringRedisTemplate.opsForGeo().search(
                        key,
                        GeoReference.fromCoordinate(x,y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs
                                .newGeoSearchArgs().includeDistance()
                                .sortAscending().limit(end)
                );
        if(results==null){
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>>
                geoResultList=results.getContent();

        if(geoResultList.size()<=from){
            return Result.ok(Collections.emptyList());
        }

        List<Long> shopIds = new ArrayList<>(
                SystemConstants.DEFAULT_PAGE_SIZE
        );

        Map<Long, Double> distanceByShopId = new HashMap<>(
                SystemConstants.DEFAULT_PAGE_SIZE
        );

        geoResultList.stream()
                .skip(from)
                .forEach(result ->{
                    Long shopId=Long.valueOf(
                            result.getContent().getName()
                    );

                    shopIds.add(shopId);
                    distanceByShopId.put(
                            shopId,
                            result.getDistance().getValue()
                    );
                });
        String idSequence = StrUtil.join(",",shopIds);


        List<Shop> shops = lambdaQuery()
                .in(Shop::getId, shopIds)
                // MySQL的IN查询不保证顺序，FIELD用于恢复Redis中的距离顺序
                .last("ORDER BY FIELD(id," + idSequence + ")")
                .list();

        // 6. distance不是数据库字段，只用于把Redis计算的距离返回给前端
        for (Shop shop : shops) {
            shop.setDistance(
                    distanceByShopId.get(shop.getId())
            );
        }

        return Result.ok(shops);

    }
}
