package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.system.oshi.CpuInfo;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import io.netty.util.internal.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    /*
    private ExecutorService CACHE_SHOP_THREADS = Executors.newFixedThreadPool(10); //线程池*/

    @Override
    public Result queryShopById(Long id) {
        // 缓存空对象解决缓存穿透
        //Shop shop= cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,ids->getById(ids),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //Shop shop = cacheClient.queryWithLock(CACHE_SHOP_KEY, id, Shop.class, LOCK_SHOP_KEY, ids -> getById(ids), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        Shop shop = cacheClient.queryWithLogicExpire(CACHE_SHOP_KEY, id, Shop.class, LOCK_SHOP_KEY, ids -> getById(ids), 10L, TimeUnit.SECONDS);
        if (shop==null){
            return Result.fail("查询商铺信息失败！");
        }else{
            return Result.ok(shop);
        }
    }

    /*
    public Shop queryWithLogicExpire(Long id){  //通过逻辑过期解决缓存击穿问题
        // 1.查询商铺缓存
        String key= CACHE_SHOP_KEY+id;
        String redisData = stringRedisTemplate.opsForValue().get(key);
        // 2.若缓存未命中，直接返回
        if(StringUtils.isBlank(redisData)){
            return null;
        }
        // 3. 缓存命中，获得缓存逻辑过期时间
        RedisData data = JSONUtil.toBean(redisData, RedisData.class);
        LocalDateTime expireTime = data.getExpireTime();
        Shop shop = JSONUtil.toBean((JSONObject) data.getData(), Shop.class);
        // 4.未过期，返回商铺信息
        if (expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        // 5.过期，需要重建缓存
        // 5.1 尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        boolean flag = tryLock(lockKey);
        // 5.2 获取互斥锁失败，直接返回过期数据
        if(!flag){
            return shop;
        }
        // 5.3 获取互斥锁成功
        // 5.3.1 DoubleCheck
        redisData = stringRedisTemplate.opsForValue().get(key);
        data = JSONUtil.toBean(redisData, RedisData.class);
        expireTime = data.getExpireTime();
        shop = JSONUtil.toBean((JSONObject) data.getData(), Shop.class);
        if (expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        // 5.3.2 开启独立线程，重建缓存
        CACHE_SHOP_THREADS.submit(()->{
            try {
                saveShopRedis(id,10L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }finally {
                // 5.3.3 释放锁
                releaseLock(lockKey);
            }
        });
        // 返回过期数据
        return shop;

    }

    public Shop queryWithLock(Long id){
        // 通过锁的方式解决缓存击穿    1.这个key高并发  2.重建key的业务较复杂

        // 1.从redis中查询商铺缓存
        String key= CACHE_SHOP_KEY+id;
        String shopStr = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中
        // 2.1 缓存命中，返回数据
        if(StringUtils.isNotBlank(shopStr)){
            return JSONUtil.toBean(shopStr, Shop.class);
        }
        // 缓存中值为空""
        if(shopStr!=null){
            return null;
        }
        String lockKey = LOCK_SHOP_KEY+id;
        Shop shop=null;
        try {
            // 2.2 缓存未命中
            // 3.重建缓存
            // 3.1 尝试获取互斥锁
            // 3.2 失败 休眠一段时间，重新执行
            boolean flag=tryLock(lockKey);
            if (!flag) {
                Thread.sleep(50);
                queryWithLock(id);
            }
            // 3.3 获取互斥锁成功
            if (flag){
                // 3.3.1 再次检查缓存中是否有数据,如果有，直接返回
                shopStr=stringRedisTemplate.opsForValue().get(key);
                if(StringUtils.isNotBlank(shopStr)){
                    return JSONUtil.toBean(shopStr,Shop.class);
                }
                // 3.3.2 执行业务（从数据库中查询）
                shop=getById(id);
                // 模拟复杂业务
                Thread.sleep(300);
                if (shop==null){
                    // 在Redis中存入空值
                    stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                    return null;
                }
                // 3.3.3 成功查询到数据，将数据写入到Redis
                shopStr = JSONUtil.toJsonStr(shop);
                stringRedisTemplate.opsForValue().set(key,shopStr,CACHE_SHOP_TTL, TimeUnit.MINUTES);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 4.释放锁
            releaseLock(lockKey);
        }
        // 返回
        return shop;
    }




    private boolean tryLock(String key){ //获取锁方法
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void releaseLock(String key){
        stringRedisTemplate.delete(key);
    }

    public Shop queryWithThrough(Long id){
        // 解决缓存穿透的代码
        // 从redis中查询商铺缓存
        String key= CACHE_SHOP_KEY+id;
        String shopStr = stringRedisTemplate.opsForValue().get(key);
        //log.debug("shopStr={}",shopStr);
        if (shopStr==null){ //缓存未命中
            Shop shop = getById(id);//从数据库查询
            if (shop==null){
                // 在Redis中存入空值
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            shopStr = JSONUtil.toJsonStr(shop);
            //写入redis
            stringRedisTemplate.opsForValue().set(key,shopStr,CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return shop;
        }
        if(StringUtils.isBlank(shopStr)){  // 缓存未命中，且未空值
            return null;
        }
        // 缓存命中
        Shop shop = JSONUtil.toBean(shopStr, Shop.class);
        return shop;
    }

     */

    public void saveShopRedis(Long id,Long expireTime) throws InterruptedException {   //缓存预热
        Shop shop = getById(id);
        Thread.sleep(200); //模拟复杂业务
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if(x==null || y==null){  // 没有地理位置，普通分页查询
             // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 按地理位置查询
        // 计算分页参数
        int start= (current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> res = stringRedisTemplate.opsForGeo().search(key,
                GeoReference.fromCoordinate(new Point(x, y)),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        ); // 获取从一开始到end的所有数据
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = res.getContent();
        if(content.size()<=start){ //没有下一页了
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = content.stream().skip(start).collect(Collectors.toList());
        Map<Long, Double> map = new HashMap<>(list.size());
        List<Long> shopIds = new ArrayList<>(list.size());
        list.stream().forEach(geoLocationGeoResult -> {
            String shopIdStr = geoLocationGeoResult.getContent().getName();
            double distance = geoLocationGeoResult.getDistance().getValue();
            map.put(Long.valueOf(shopIdStr), distance);
            shopIds.add(Long.valueOf(shopIdStr));
        });
        // 根据shopId按顺序查出所有店铺信息
        String idStr = StrUtil.join(",",shopIds);
        List<Shop> shops = query().in("id", shopIds).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            // 设置距离
            shop.setDistance(map.get(shop.getId()));
        }
        return Result.ok(shops);
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long shopId = shop.getId();
        if (shopId==null){
            return Result.fail("店铺的id不能为空");
        }
        // 先操作数据库
        updateById(shop);
        // 再删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
        return Result.ok();
    }
}
