-- KEYS[1]：秒杀券库存key，例如 seckill:stock:10
-- KEYS[2]：已下单用户集合key，例如 seckill:order:10
-- ARGV[1]：当前用户ID

-- 1. 读取Redis库存
local stock = tonumber(redis.call('get', KEYS[1]))

-- 库存key不存在，说明该秒杀券尚未初始化到Redis
if not stock then
    return 3
end

-- 2. Redis库存不足时直接拒绝，不再访问MySQL
if stock <= 0 then
    return 1
end

-- 3. 使用Set判断当前用户是否已经取得该优惠券的下单资格
if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then
    return 2
end

-- 4. 库存校验和库存扣减必须位于同一个Lua脚本中，避免并发超卖
redis.call('incrby', KEYS[1], -1)

-- 5. 记录用户资格，后续请求可以在Redis中完成一人一单判断
redis.call('sadd', KEYS[2], ARGV[1])

-- 资格集合沿用库存key的剩余有效期，避免秒杀结束后无期限占用Redis内存
local stockTtl = redis.call('pttl', KEYS[1])
if stockTtl > 0 then
    redis.call('pexpire', KEYS[2], stockTtl)
end

return 0
