package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 从 Redis Sorted Set 查询店铺类型
        Set<String> typeSet = stringRedisTemplate.opsForZSet().range(CACHE_SHOP_TYPE_KEY, 0, -1);
        if (typeSet != null && !typeSet.isEmpty()) {
            List<ShopType> typeList = typeSet.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(typeList);
        }

        // 不存在，从数据库查询并明确按 sort 排序，避免数据库默认顺序不稳定
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (typeList == null || typeList.isEmpty()) {
            return Result.fail("店铺类型不存在!");
        }

        // sort 为空时无法保证 Redis 中的排序与数据库一致，本次只返回数据库结果，不写入不完整缓存。
        boolean cacheable = typeList.stream().allMatch(shopType -> shopType.getSort() != null);
        if (cacheable) {
            for (ShopType shopType : typeList) {
                stringRedisTemplate.opsForZSet().add(
                        CACHE_SHOP_TYPE_KEY,
                        JSONUtil.toJsonStr(shopType),
                        shopType.getSort().doubleValue()
                );
            }
            // Sorted Set 需要给整个 key 设置 TTL，避免类型数据永久不更新。
            stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        }

        return Result.ok(typeList);
    }
}
