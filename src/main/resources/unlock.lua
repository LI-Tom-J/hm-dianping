-- 比较锁的持有者标识和当前线程标识，只有匹配时才能删除锁
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
end

return 0
