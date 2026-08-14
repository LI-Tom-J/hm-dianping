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

    /**
     * 秒杀库存使用String结构，并在秒杀活动结束时自动过期。
     */
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";

    /**
     * 已获得秒杀资格的用户使用Set结构：
     * key为seckill:order:{voucherId}，成员为userId。
     *
     * Set能够通过SISMEMBER快速判断用户是否已经下过单。
     */
    public static final String SECKILL_ORDER_KEY = "seckill:order:";

    /**
     * 秒杀订单消息使用Redis Stream持久化，避免JVM重启后内存队列中的订单丢失。
     */
    public static final String SECKILL_ORDER_STREAM_KEY = "stream.orders";

    /**
     * 同一消费组内的消费者共同分担订单消息，并通过ACK维护待处理列表。
     */
    public static final String SECKILL_ORDER_STREAM_GROUP = "g1";

    /**
     * 当前项目先按单实例教学运行，固定消费者名称便于应用重启后读取自己的Pending消息。
     */
    public static final String SECKILL_ORDER_STREAM_CONSUMER = "c1";

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
