package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    void testSetShopLogicalExpireCache() {
        Shop shop = shopService.getById(1L);
        assertNotNull(shop);

        String key = CACHE_SHOP_KEY + shop.getId();
        try {
            cacheClient.setWithLogicalExpire(key, shop, 10L, TimeUnit.SECONDS);

            String cached = stringRedisTemplate.opsForValue().get(key);
            assertNotNull(cached);
            Shop result = cacheClient.queryWithLogicalExpire(
                    CACHE_SHOP_KEY, shop.getId(), Shop.class, ignored -> {
                        throw new AssertionError("未过期缓存不应查询数据库");
                    }, 10L, TimeUnit.SECONDS
            );
            assertNotNull(result);
            assertEquals(shop.getId(), result.getId());
        } finally {
            stringRedisTemplate.delete(key);
        }
    }

    @Test
    void testIdWorker() throws Exception {
        int taskCount = 32;
        int idsPerTask = 100;
        String keyPrefix = "test-order";
        String counterKey = "icr:" + keyPrefix + ":"
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>(taskCount);

        try {
            for (int i = 0; i < taskCount; i++) {
                futures.add(executor.submit(() -> {
                    for (int j = 0; j < idsPerTask; j++) {
                        ids.add(redisIdWorker.nextId(keyPrefix));
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
            assertEquals(taskCount * idsPerTask, ids.size(), "并发生成的订单 ID 不应重复");
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            stringRedisTemplate.delete(counterKey);
        }
    }
}
