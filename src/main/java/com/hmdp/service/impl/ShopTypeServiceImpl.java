package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> getTypeList() {
    String key=RedisConstants.CACHE_SHOP_TYPE_KEY;
    String typeJson=stringRedisTemplate.opsForValue().get(key);

    if(StrUtil.isNotBlank(typeJson)){
        return JSONUtil.toList(typeJson,ShopType.class);
    }
    List<ShopType> typeList =query()
            .orderByAsc("sort")
            .list();
    stringRedisTemplate.opsForValue().set(
            key,
            JSONUtil.toJsonStr(typeList),
            RedisConstants.CACHE_SHOP_TTL,
            TimeUnit.MINUTES
    );

        return typeList;
    }
}
