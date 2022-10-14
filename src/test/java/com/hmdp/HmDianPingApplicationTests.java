package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    private ExecutorService es= Executors.newFixedThreadPool(300);

    @Autowired
    private IShopService shopService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        // 每个线程生成100个id
        Long start = System.currentTimeMillis();
        Runnable task= ()->{
            for (int i=0;i<100;i++){
                Long id = redisIdWorker.nextId("order");
                System.out.println("id="+id);
            }
            latch.countDown(); // CountDownLatch内部维护的变量减一
        };
        for (int i=0;i<300;i++){
            es.submit(task);
        }
        latch.await();  // main 线程阻塞，只有当CountDownLatch内部维护的变量=0，主线程才会被唤醒
        // 所以，使用CountDownLatch先把分线程执行完
        Long end = System.currentTimeMillis();
        System.out.println("耗时"+(end-start));
    }

    @Test
    void testSaveRedis() throws InterruptedException {
        shopService.saveShopRedis(1L,10L);
    }

    @Test
    void loadRedisData(){
        // 将店铺经纬度信息保存到redis
        // 查询所有的店铺信息
        List<Shop> shopList = shopService.list();
        // 按照店铺类型进行分类
        Map<Long, List<Shop>> map = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            List<Shop> value = entry.getValue();
            String key = RedisConstants.SHOP_GEO_KEY + typeId;
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            value.stream().forEach(shop -> {
                // 第一个参数就是对应的message  第二个参数经纬度
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
            });
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }
}
