package com.hmdp.utils;

/**
 * description: ILock <br>
 * date: 2022/9/26 21:20 <br>
 * author: shatianhui <br>
 * version: 1.0 <br>
 */
public interface ILock {
    /**
     * 尝试获取锁，使用的是非阻塞式的
     * @param timeoutSec 锁过期时间 单位s
     * @return true表示获取锁成功 false表示获取锁失败
     */
    boolean tryLock(Long timeoutSec);
    void unlock();
}
