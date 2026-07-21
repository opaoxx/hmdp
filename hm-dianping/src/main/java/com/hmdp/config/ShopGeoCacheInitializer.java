package com.hmdp.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@Slf4j
@Component
public class ShopGeoCacheInitializer implements ApplicationRunner {

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    public void reload() {
        List<Shop> shops = shopMapper.selectList(new QueryWrapper<Shop>()
                .select("id", "type_id", "x", "y"));
        Map<Long, List<Shop>> shopsByType = shops.stream()
                .filter(shop -> shop.getTypeId() != null && shop.getX() != null && shop.getY() != null)
                .collect(Collectors.groupingBy(Shop::getTypeId));

        int loaded = 0;
        for (Map.Entry<Long, List<Shop>> entry : shopsByType.entrySet()) {
            String key = SHOP_GEO_KEY + entry.getKey();
            stringRedisTemplate.delete(key);

            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(entry.getValue().size());
            for (Shop shop : entry.getValue()) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(), new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
            loaded += locations.size();
        }
        log.info("店铺 GEO 缓存预热完成，加载店铺数量：{}，店铺类型数量：{}", loaded, shopsByType.size());
    }
}
