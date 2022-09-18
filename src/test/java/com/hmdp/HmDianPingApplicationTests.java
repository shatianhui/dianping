package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
    private ExecutorService es= Executors.newFixedThreadPool(300);

    @Autowired
    private IShopService shopService;

    @Autowired
    private RedisIdWorker redisIdWorker;

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
}
