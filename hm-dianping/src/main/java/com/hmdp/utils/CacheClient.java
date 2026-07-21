package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <ID, T> T queryWithPassThrough(String keyPrefix, ID id, Class<T> type,
                                          Function<ID, T> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        T t = dbFallback.apply(id);
        if (t == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        set(key, t, time, unit);
        return t;
    }

    public <ID, T> T queryWithMutex(String keyPrefix, ID id, Class<T> type,
                                    Function<ID, T> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        String lockKey = LOCK_SHOP_KEY + id;
        while (true) {
            String lockValue = tryLock(lockKey);
            if (lockValue == null) {
                sleepBeforeRetry();
                String latestJson = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(latestJson)) {
                    return JSONUtil.toBean(latestJson, type);
                }
                if (latestJson != null) {
                    return null;
                }
                continue;
            }

            try {
                // 二次检查，避免多个线程重复查询数据库
                String json2 = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(json2)) {
                    return JSONUtil.toBean(json2, type);
                }
                if (json2 != null) {
                    return null;
                }

                T t = dbFallback.apply(id);
                if (t == null) {
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(t), time, unit);
                return t;
            } finally {
                // 只有持有当前令牌的线程才能删除锁
                unLock(lockKey, lockValue);
            }
        }
    }

    public <ID, T> T queryWithLogicalExpire(String keyPrefix, ID id, Class<T> type,
                                            Function<ID, T> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            return rebuildLogicalCacheOnMiss(key, id, type, dbFallback, time, unit);
        }
        if (StrUtil.isBlank(json)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        T t = convertData(redisData, type);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return t;
        }

        String lockKey = LOCK_SHOP_KEY + id;
        String lockValue = tryLock(lockKey);
        if (lockValue != null) {
            boolean rebuildSubmitted = false;
            try {
                // 二次检查，避免重复提交缓存重建任务
                String json2 = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(json2)) {
                    RedisData redisData2 = JSONUtil.toBean(json2, RedisData.class);
                    T t2 = convertData(redisData2, type);
                    if (redisData2.getExpireTime().isAfter(LocalDateTime.now())) {
                        return t2;
                    }
                }

                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        T fresh = dbFallback.apply(id);
                        if (fresh == null) {
                            // 数据已删除时，删除旧的逻辑过期缓存，避免反复返回脏数据
                            stringRedisTemplate.delete(key);
                        } else {
                            setWithLogicalExpire(key, fresh, time, unit);
                        }
                    } catch (Exception e) {
                        log.error("异步重建店铺缓存失败，id={}", id, e);
                    } finally {
                        unLock(lockKey, lockValue);
                    }
                });
                rebuildSubmitted = true;
            } catch (Exception e) {
                log.error("提交店铺缓存重建任务失败，id={}", id, e);
            } finally {
                // 提交成功后由异步线程释放；提交失败则当前线程释放
                if (!rebuildSubmitted) {
                    unLock(lockKey, lockValue);
                }
            }
        }
        return t;
    }

    private <ID, T> T rebuildLogicalCacheOnMiss(String key, ID id, Class<T> type,
                                                  Function<ID, T> dbFallback, Long time, TimeUnit unit) {
        String lockKey = LOCK_SHOP_KEY + id;
        while (true) {
            String lockValue = tryLock(lockKey);
            if (lockValue == null) {
                sleepBeforeRetry();
                String latestJson = stringRedisTemplate.opsForValue().get(key);
                if (latestJson == null) {
                    continue;
                }
                if (StrUtil.isBlank(latestJson)) {
                    return null;
                }
                RedisData latestData = JSONUtil.toBean(latestJson, RedisData.class);
                return convertData(latestData, type);
            }

            try {
                String latestJson = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(latestJson)) {
                    RedisData latestData = JSONUtil.toBean(latestJson, RedisData.class);
                    if (latestData.getExpireTime().isAfter(LocalDateTime.now())) {
                        return convertData(latestData, type);
                    }
                } else if (latestJson != null) {
                    return null;
                }

                T fresh = dbFallback.apply(id);
                if (fresh == null) {
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                setWithLogicalExpire(key, fresh, time, unit);
                return fresh;
            } finally {
                unLock(lockKey, lockValue);
            }
        }
    }

    private String tryLock(String key) {
        String lockValue = UUID.randomUUID().toString();
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(
                key, lockValue, LOCK_SHOP_TTL, TimeUnit.SECONDS
        );
        return Boolean.TRUE.equals(flag) ? lockValue : null;
    }

    private void unLock(String key, String lockValue) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), lockValue);
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待缓存互斥锁时线程被中断", e);
        }
    }

    private <T> T convertData(RedisData redisData, Class<T> type) {
        if (redisData == null || redisData.getData() == null) {
            return null;
        }
        return JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
    }

    @PreDestroy
    public void shutdown() {
        CACHE_REBUILD_EXECUTOR.shutdown();
    }
}
