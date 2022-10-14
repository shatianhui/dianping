package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * description: RedissonConfig <br>
 * date: 2022/10/3 9:51 <br>
 * author: shatianhui <br>
 * version: 1.0 <br>
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.174.100:6379").setPassword("781781sth");
        return Redisson.create(config);
    }
}
