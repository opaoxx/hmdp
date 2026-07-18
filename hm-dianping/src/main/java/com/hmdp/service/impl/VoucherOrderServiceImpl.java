package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    private static final String ORDER_STREAM = "stream.orders";
    private static final String ORDER_GROUP = "g1";
    private static final String ORDER_CONSUMER = "c1";
    private static final String LOCK_PREFIX = "lock:order:";

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final StringRedisSerializer STRING_SERIALIZER = new StringRedisSerializer();

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Autowired
    private ObjectProvider<IVoucherOrderService> proxyProvider;

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @PreDestroy
    private void destroy() {
        SECKILL_ORDER_EXECUTOR.shutdownNow();
    }

    /**
     * 幂等创建消息消费组。MKSTREAM 允许首次启动时 stream.orders 尚不存在。
     */
    private void ensureConsumerGroup() {
        try {
            stringRedisTemplate.execute((RedisCallback<String>) connection -> connection.streamCommands()
                    .xGroupCreate(
                            STRING_SERIALIZER.serialize(ORDER_STREAM),
                            ORDER_GROUP,
                            ReadOffset.from("0-0"),
                            true
                    ));
        } catch (DataAccessException e) {
            // 消费组已存在属于正常情况，其他 Redis 异常必须继续向上抛出并稍后重试。
            if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ensureConsumerGroup();
                } catch (Exception e) {
                    log.error("初始化订单消息消费组失败", e);
                    sleepBeforeRetry();
                    continue;
                }

                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_GROUP, ORDER_CONSUMER),
                            org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                                    .count(1)
                                    .block(Duration.ofSeconds(2)),
                            StreamOffset.create(ORDER_STREAM, ReadOffset.lastConsumed())
                    );
                    if (records == null || records.isEmpty()) {
                        continue;
                    }
                    acknowledgeAfterProcessing(records.get(0));
                } catch (Exception e) {
                    log.error("处理订单消息异常，开始处理 pending 消息", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_GROUP, ORDER_CONSUMER),
                            org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                                    .count(1),
                            // 0-0 表示读取当前消费者的 pending 消息，而不是重新读取新消息
                            StreamOffset.create(ORDER_STREAM, ReadOffset.from("0-0"))
                    );
                    if (records == null || records.isEmpty()) {
                        return;
                    }
                    acknowledgeAfterProcessing(records.get(0));
                } catch (Exception e) {
                    log.error("处理 pending 订单消息异常", e);
                    sleepBeforeRetry();
                }
            }
        }

        private void acknowledgeAfterProcessing(MapRecord<String, Object, Object> record) {
            Map<Object, Object> values = record.getValue();
            VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
            handleVoucherOrder(voucherOrder);
            stringRedisTemplate.opsForStream().acknowledge(ORDER_STREAM, ORDER_GROUP, record.getId());
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }

        // 必须在 Lua 扣库存、写入下单标记和发送消息之前完成时间校验。
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (voucher.getBeginTime().isAfter(now)) {
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(now)) {
            return Result.fail("秒杀已经结束");
        }

        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                user.getId().toString(),
                String.valueOf(orderId)
        );
        if (result == null) {
            return Result.fail("秒杀失败");
        }
        if (result != 0) {
            return Result.fail(result == 1 ? "库存不足" : "请勿重复下单");
        }
        return Result.ok(orderId);
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock(LOCK_PREFIX + userId);
        if (!lock.tryLock()) {
            // 不 ACK，交给 pending 流程稍后重试，避免订单消息丢失。
            throw new IllegalStateException("获取订单锁失败，稍后重试");
        }
        try {
            // 异步线程中通过 Provider 获取 Spring 代理，确保事务拦截器生效。
            proxyProvider.getObject().createVoucherOrder(voucherOrder);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if (count > 0) {
            // 这是可预期的幂等结果，直接结束，外层随后 ACK 消息。
            log.info("用户已购买该优惠券，userId={}, voucherId={}", userId, voucherOrder.getVoucherId());
            return;
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            // 库存不足时不能继续创建订单。
            log.info("数据库库存不足，voucherId={}", voucherOrder.getVoucherId());
            return;
        }

        if (!save(voucherOrder)) {
            throw new IllegalStateException("创建订单失败");
        }
    }
}
