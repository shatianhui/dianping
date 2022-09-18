package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;

/**
 * description: 缓存工具类 <br>
 * date: 2022/9/15 19:35 <br>
 * author: shatianhui <br>
 * version: 1.0 <br>
 */
@Component
@Slf4j
public class CacheClient {

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10); // 线程池

    private final StringRedisTemplate stringRedisTemplate;

    // 构造方法注入
    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }

    /**
     * 将任意类型的对象存储在string类型的key中，并设置过期时间
     * @param key
     * @param value
     * @param expire
     * @param unit
     */
    public void set(String key, Object value, Long expire, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),expire,unit);
    }

    /**
     * 将任意类型的对象存储在string类型的key中，并设置逻辑过期时间，用于解决缓存击穿问题
     * @param key
     * @param value
     * @param expire
     * @param unit
     */
    public void setWithLogicExpire(String key,Object value,Long expire,TimeUnit unit){
        RedisData data = new RedisData();
        data.setData(value);
        data.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(expire)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(data));
    }

    /**
     * 缓存空对象解决缓存穿透问题
     * @param keyPrefix
     * @param id
     * @param type
     * @param ops  操作数据库的函数式接口，回调（不确定的代码）
     * @param expire
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> ops,Long expire, TimeUnit unit){
        // 1. 查询缓存
        String key=keyPrefix+id;
        String value = stringRedisTemplate.opsForValue().get(key);
        // 2. 缓存命中，返回
        if (StringUtils.isNotBlank(value)){
            return JSONUtil.toBean(value,type);
        }
        // 3. 缓存为空字符串，返回null
        if(value!=null){
            return null;
        }
        // 4. 未命中，操作数据库
        R r = ops.apply(id);
        // 5. 数据库数据不存在，写入空值
        if(r==null){
            set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        // 6.数据库数据存在
        set(key,r,expire,unit);
        return r;
    }

    /**
     * 通过互斥锁解决缓存击穿问题( 1.这个key高并发  2.重建key的业务较复杂 )
     * @param keyPrefix
     * @param id
     * @param type
     * @param lockKeyPrefix
     * @param ops
     * @param expire
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R,ID> R queryWithLock(String keyPrefix,ID id,Class<R> type,String lockKeyPrefix,Function<ID,R> ops,Long expire,TimeUnit unit){
        // 1.从redis中查询缓存
        String key= keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中
        // 2.1 缓存命中，返回数据
        if(StringUtils.isNotBlank(json)){
            return JSONUtil.toBean(json,type);
        }
        // 缓存值为空""  空缓存
        if(json!=null){
            return null;
        }
        String lockKey = lockKeyPrefix+id;
        R r=null;
        try {
            // 2.2 缓存未命中
            // 3.重建缓存
            // 3.1 尝试获取互斥锁
            // 3.2 失败 休眠一段时间，重新执行
            boolean flag=tryLock(lockKey);
            if (!flag) {
                Thread.sleep(50);
                queryWithLock(keyPrefix,id,type,lockKeyPrefix,ops,expire,unit);
            }
            // 3.3 获取互斥锁成功
            if (flag){
                // 3.3.1 再次检查缓存中是否有数据,如果有，直接返回  double check
                json=stringRedisTemplate.opsForValue().get(key);
                if(StringUtils.isNotBlank(json)){
                    return JSONUtil.toBean(json,type);
                }
                // 3.3.2 执行业务（从数据库中查询）
                r=ops.apply(id);
                // 模拟复杂业务
                Thread.sleep(300);
                // 存入空缓存解决缓存穿透
                if (r==null){
                    // 在Redis中存入空值
                    stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                    return null;
                }
                // 3.3.3 成功查询到数据，将数据写入到Redis
                set(key,r,expire,unit);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 4.释放锁
            releaseLock(lockKey);
        }
        // 返回
        return r;
    }

    /**
     * 通过逻辑过期解决缓存击穿问题, 需要进行缓存预热
     * @param keyPrefix
     * @param id
     * @param type
     * @param lockKeyPrefix
     * @param ops
     * @param expire
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R,ID> R queryWithLogicExpire(String keyPrefix,ID id,Class<R> type,String lockKeyPrefix,Function<ID,R> ops,Long expire,TimeUnit unit){
        // 1.查询商铺缓存
        String key= keyPrefix+id;
        String redisData = stringRedisTemplate.opsForValue().get(key);
        // 2.若缓存未命中，直接返回
        if(StringUtils.isBlank(redisData)){
            return null;
        }
        // 3. 缓存命中，获得缓存逻辑过期时间
        RedisData data = JSONUtil.toBean(redisData, RedisData.class);
        LocalDateTime expireTime = data.getExpireTime();
        R r = JSONUtil.toBean((JSONObject) data.getData(), type);
        // 4.未过期，返回信息
        if (expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        // 5.过期，需要重建缓存
        // 5.1 尝试获取互斥锁
        String lockKey = lockKeyPrefix+id;
        boolean flag = tryLock(lockKey);
        // 5.2 获取互斥锁失败，直接返回过期数据
        if(!flag){
            return r;
        }
        // 5.3 获取互斥锁成功
        // 5.3.1 DoubleCheck
        redisData = stringRedisTemplate.opsForValue().get(key);
        data = JSONUtil.toBean(redisData, RedisData.class);
        expireTime = data.getExpireTime();
        r = JSONUtil.toBean((JSONObject) data.getData(), type);
        if (expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        // 5.3.2 开启独立线程，重建缓存
        CACHE_REBUILD_EXECUTOR.submit(()->{
            try {
                R r1 = ops.apply(id);
                Thread.sleep(200); //模拟复杂业务
                setWithLogicExpire(key,r1,expire,unit);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }finally {
                // 5.3.3 释放锁
                releaseLock(lockKey);
            }
        });
        // 返回过期数据
        return r;
    }

    /**
     * 尝试获取互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     * @param key
     */
    private void releaseLock(String key){
        stringRedisTemplate.delete(key);
    }
}
