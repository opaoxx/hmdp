package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.ShopGeoCacheInitializer;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopGeoCacheInitializer shopGeoCacheInitializer;

    @Override
    public Result queryById(Long id) {
        Shop shop = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES
        );
        if (shop == null) {
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);
    }

    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空!");
        }
        boolean updated = updateById(shop);
        if (!updated) {
            return Result.fail("店铺不存在或更新失败!");
        }
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        shopGeoCacheInitializer.reload();
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, String sortBy, Double x, Double y) {
        boolean sortByComments = "comments".equals(sortBy);
        boolean sortByScore = "score".equals(sortBy);
        if (x == null || y == null) {
            Page<Shop> page;
            if (sortByComments || sortByScore) {
                page = query().eq("type_id", typeId)
                        .orderByDesc(sortBy)
                        .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            } else {
                page = query().eq("type_id", typeId)
                        .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            }
            return Result.ok(page.getRecords());
        }

        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        RedisGeoCommands.GeoSearchCommandArgs searchArgs =
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance();
        if (!sortByComments && !sortByScore) {
            searchArgs.sortAscending().limit(end);
        }
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key, GeoReference.fromCoordinate(x, y), new Distance(5000), searchArgs);
        if (results == null || results.getContent().isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : list) {
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            distanceMap.put(shopId, result.getDistance());
        }
        List<Shop> shops = query().in("id", ids).list();
        for (Shop shop : shops) {
            Distance distance = distanceMap.get(shop.getId().toString());
            if (distance != null) {
                shop.setDistance(distance.getValue());
            }
        }
        if (sortByComments) {
            shops.sort(Comparator.comparing(Shop::getComments,
                    Comparator.nullsLast(Comparator.reverseOrder())));
        } else if (sortByScore) {
            shops.sort(Comparator.comparing(Shop::getScore,
                    Comparator.nullsLast(Comparator.reverseOrder())));
        } else {
            shops.sort(Comparator.comparing(Shop::getDistance,
                    Comparator.nullsLast(Comparator.naturalOrder())));
        }
        int start = Math.min(from, shops.size());
        int finish = Math.min(end, shops.size());
        return Result.ok(shops.subList(start, finish));
    }
}
