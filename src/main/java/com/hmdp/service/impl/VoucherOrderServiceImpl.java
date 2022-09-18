package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
        synchronized (userId.toString().intern()){
            // 获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(id);
        }  // 先创建订单，提交事务后再释放锁
    }

    @Transactional
    public Result createVoucherOrder(Long id) {
        Long userId = UserHolder.getUser().getId();
        // 判断是否是一人一单
        LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(VoucherOrder::getUserId,userId);
        queryWrapper.eq(VoucherOrder::getVoucherId, id);
        int count = count(queryWrapper);
        if (count>0){
            return Result.fail("你已领取优惠券！");
        }
        //更新库存
        boolean flag = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", id).gt("stock",0).update();
        if (!flag){
            return Result.fail("库存不足");
        }
        // 4. 创建订单 ，返回订单id
        VoucherOrder order = new VoucherOrder();
        Long orderId = redisIdWorker.nextId("order");
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(id);
        boolean save = save(order);
        if (!save){
            return Result.fail("订单创建失败！");
        }
        return Result.ok(orderId);
    }
}
