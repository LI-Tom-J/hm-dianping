package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    /**
     * 秒杀订单锁按用户ID区分，保证同一用户的下单请求串行执行。
     */
    public static final String LOCK_ORDER_KEY = "lock:order:";

    /**
     * 锁必须设置过期时间，避免服务异常退出后产生永久死锁。
     */
    public static final Long LOCK_ORDER_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shopType";

    /**
     * 全局唯一ID的Redis自增序列前缀
     */
    public static final String ID_INCREMENT_KEY_PREFIX = "icr:";
}
