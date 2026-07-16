package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断查询是否为空
        if(voucher == null){
            return Result.fail("优惠券不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        //判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(now)){
            return Result.fail("秒杀尚未开始");
        }
        //判断秒杀是否结束
        if(voucher.getEndTime().isBefore(now)){
            return Result.fail("秒杀已经结束");
        }
        //判断库存是否充足
        if(voucher.getStock() < 1){
            return Result.fail("库存不足");
        }

        //创建锁对象
        Long userId = UserHolder.getUser().getId();
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //获取锁
        boolean isLock = lock.tryLock(120L);
        if(!isLock){
            return Result.fail("请勿重复下单");
        }
        try{
            //获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId, userId);
        }
        finally{
            //释放锁
            lock.unlock();
        }
    }

    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId, Long userId){
        //一人一单，不可重复下单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0){
            return Result.fail("您已购买过一次,不可重复下单!");
        }

        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
//                .eq("stock", voucher.getStock())
                .gt("stock", 0)
                .update();
        if(!success){
            return Result.fail("库存不足");
        }

        //创建订单,包括订单id，用户id，代金券id
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        boolean save = save(voucherOrder);
        if(!save){
            // 保存失败必须抛出异常，触发事务回滚，避免出现只扣库存不生成订单的情况
            throw new IllegalStateException("创建订单失败");
        }
        //返回订单ID
        return Result.ok(orderId);
    }
}
