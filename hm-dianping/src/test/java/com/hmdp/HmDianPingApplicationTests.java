package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

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
}
