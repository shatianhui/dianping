package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.BeanUtils;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //private BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<VoucherOrder>(1024*1024);
    private static final ExecutorService SECKILL_EXECUTER = Executors.newSingleThreadExecutor();

    private class Task implements Runnable{  //内部类
        String queueName = "stream.orders";
        @Override
        public void run() {  //这里锁使用不使用都可以，因为已经用了redis
            while (true){
                try {
                    // 尝试从消息队列中读取一条消息，阻塞2s  >表示从下一个未被处理的消息，开始
                    // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    if(list==null || list.isEmpty()){
                        // 说明，消息队列没有消息，下一次循环
                        continue;
                    }
                    // map的key就是消息的id
                    MapRecord<String, Object, Object> map = list.get(0);
                    Map<Object, Object> value = map.getValue();
                    // map中数据转化成实体bean
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                    // 创建完订单一点要ACK
                    // XACK stream.orders g1 消息Id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", map.getId());
                } catch (Exception e) {
                    log.error("异步下单任务出错",e);
                    // 出现异常，该消息未被处理完，需要去peeding-list中处理
                    handlePeedingList();
                }
            }
        }

        private void handlePeedingList() {
            while (true){
                try {
                    // 尝试从peedinglist中读取一条消息  0表示pending-list中的第一个消息
                    // XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0")));
                    if(list==null || list.isEmpty()){
                        // 说明，peedinglist没有消息，结束循环
                        break;
                    }
                    // map的key就是消息的id
                    MapRecord<String, Object, Object> map = list.get(0);
                    Map<Object, Object> value = map.getValue();
                    // map中数据转化成实体bean
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                    // 创建完订单一点要ACK
                    // XACK stream.orders g1 消息Id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", map.getId());
                } catch (Exception e) {
                    log.error("peeding-List下单任务出错",e);
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                    continue;//继续循环
                }
            }
        }

    }
    /*
    private class Task implements Runnable{  //内部类

        @Override
        public void run() {  //这里锁使用不使用都可以，因为已经用了redis
            while (true){
                try {
                    // 从阻塞队列中取出一个订单，若没有，则阻塞，只有有了才会被唤醒
                    VoucherOrder voucherOrder = orderTask.take();
                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("异步下单任务出错",e);
                }
            }
        }

    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();

        // 获取锁对象，锁的范围 相同userid  不同userID就不需要上锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean flag = lock.tryLock();
        if (!flag){ //获取锁失败
            log.error("一人只能下一单");
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw e;
        } finally {
            lock.unlock();
        }
    }

    @PostConstruct   //初始化方法,类初始化方法
    private void init(){
        // 开启异步下单任务
        SECKILL_EXECUTER.submit(new Task());
    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT= new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class); //返回结果必须是Long型
    }

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) { // 优惠券id
        Long userId = UserHolder.getUser().getId();
        Long orderID = redisIdWorker.nextId("order");
        // 执行lua脚本
        Long res = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderID.toString());
        int r = res.intValue();
        if(r!=0){
            return Result.fail(r == 1 ? "库存不足！" : "一个用户只能下一单");
        }
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderID);//返回订单id
    }

    /*
    @Override
    public Result seckillVoucher(Long id) {
        // 1.查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(id);
        if (seckillVoucher==null){
            return Result.fail("未查询到此优惠券");
        }
        // 2.看优惠券是否开始和结束
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("抢购未开始");
        }
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("抢购已结束");
        }
        // 3.看库存是否足够
        if(seckillVoucher.getStock()<=0){
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();

        // 获取锁对象，锁的范围 相同userid  不同userID就不需要上锁
        //SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean flag = lock.tryLock();
        if (!flag){ //获取锁失败
            return Result.fail("一人只能下一单");
        }
        try {
            // 获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(id);
        } catch (IllegalStateException e) {
            throw e;
        } finally {
            lock.unlock();
        }
    }
*/
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId =voucherOrder.getUserId();
        // 判断是否是一人一单
        LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(VoucherOrder::getUserId,userId);
        queryWrapper.eq(VoucherOrder::getVoucherId, voucherOrder.getVoucherId());
        int count = count(queryWrapper);
        if (count>0){
            log.error("你已领取优惠券！");
        }
        //更新库存
        boolean flag = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0).update();
        if (!flag){
            log.error("库存不足");
        }
        // 4. 创建订单
        boolean save = save(voucherOrder);
        if (!save){
            log.error("订单创建失败！");
        }
    }

}
