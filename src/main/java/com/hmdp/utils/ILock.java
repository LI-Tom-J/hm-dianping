package com.hmdp.utils;

import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlLockTableStatement;

public interface ILock {

    /**
     * 尝试获取锁。
     *
     * @param timeoutSec 锁的自动过期时间，单位为秒
     * @return true表示成功获得锁，false表示锁已被其他请求持有
     */
    boolean tryLock(Long timeoutSec);
    /**
     * 释放当前请求持有的锁。
     */
    void unlock();

}
