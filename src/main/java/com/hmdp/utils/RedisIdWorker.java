package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * description: 全局id生成器 基于redis <br>
 * date: 2022/9/16 17:15 <br>
 * author: shatianhui <br>
 * version: 1.0 <br>
 */

@Component
public class RedisIdWorker {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    // 序列号位数
    private static final int COUNTS_BITS=32;
    // 开始时间戳
    private Long BEGIN_TIMESTAMP = LocalDateTime.of(2022, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC);
    public Long nextId(String keyPrefix){
        // 时间戳
        LocalDateTime now = LocalDateTime.now();
        Long timestamp=now.toEpochSecond(ZoneOffset.UTC)-BEGIN_TIMESTAMP;
        // 序列号 自增长
        // 获取当前日期  （1. 每天一个key 2. 方便后面统计，比如月订单量，日订单量）
        String date=now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long increment = stringRedisTemplate.opsForValue().increment("inc:" + keyPrefix + ":" + date);
        return timestamp<<32 | increment;
    }
}
