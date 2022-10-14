package com.hmdp;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

/**
 * description: RedissonTest <br>
 * date: 2022/10/5 19:28 <br>
 * author: shatianhui <br>
 * version: 1.0 <br>
 */

@Slf4j
@SpringBootTest
public class RedissonTest {
    @Autowired
    private RedissonClient redissonClient;

    private RLock lock;

    @BeforeEach
    void setUp() {
        lock = redissonClient.getLock("lock:order:");
    }

    @Test
    void method1() throws InterruptedException {
        boolean islock = lock.tryLock(1L, TimeUnit.SECONDS);
        if (!islock){
            log.info("获取锁失败....1");
            return;
        }
        try{
            log.info("获取锁成功....1");
            method2();
            log.info("开始执行业务....1");
        }finally {
            log.warn("开始释放锁....1");
            lock.unlock();
        }
    }

    void method2(){
        boolean islock = lock.tryLock();
        if (!islock){
            log.info("获取锁失败....2");
            return;
        }
        try{
            log.info("获取锁成功....2");
            log.info("开始执行业务....2");
        }finally {
            log.warn("开始释放锁....2");
            lock.unlock();
        }
    }
}
