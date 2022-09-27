package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * description: SimpleRedisLock <br>
 * date: 2022/9/26 21:23 <br>
 * author: shatianhui <br>
 * version: 1.0 <br>
 */
public class SimpleRedisLock implements ILock{
    private String name; //业务名称
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";

    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate){
        this.name=name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(Long timeoutSec) {
        // value存放UUID拼接当前线程id
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success); // 避免拆箱而引起的空指针异常
    }

    @Override
    public void unlock() {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 判断锁标识是否是自己
        if(threadId.equals(stringRedisTemplate.opsForValue().get(KEY_PREFIX+name))){
            // 是自己的锁就释放
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
