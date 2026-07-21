package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IStatisticsService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.BLOG_HOT_UV_KEY;
import static com.hmdp.utils.RedisConstants.BLOG_HOT_UV_TTL;

@Slf4j
@Service
public class StatisticsServiceImpl implements IStatisticsService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void recordBlogHotVisitor(HttpServletRequest request) {
        String visitorKey = resolveVisitorKey(request);
        if (StrUtil.isBlank(visitorKey)) {
            return;
        }

        String key = BLOG_HOT_UV_KEY + LocalDate.now();
        try {
            stringRedisTemplate.opsForHyperLogLog().add(key, visitorKey);
            stringRedisTemplate.expire(key, BLOG_HOT_UV_TTL, TimeUnit.DAYS);
        } catch (RuntimeException e) {
            log.warn("记录热门博客 UV 失败，key={}", key, e);
        }
    }

    @Override
    public Result queryBlogHotUv() {
        String key = BLOG_HOT_UV_KEY + LocalDate.now();
        Long count = stringRedisTemplate.opsForHyperLogLog().size(key);
        return Result.ok(count == null ? 0L : count);
    }

    private String resolveVisitorKey(HttpServletRequest request) {
        UserDTO user = UserHolder.getUser();
        if (user != null && user.getId() != null) {
            return "user:" + user.getId();
        }

        String token = request.getHeader("authorization");
        if (StrUtil.isNotBlank(token)) {
            return "token:" + SecureUtil.md5(token);
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        String clientIp = StrUtil.isBlank(forwardedFor)
                ? request.getRemoteAddr()
                : forwardedFor.split(",")[0].trim();
        String userAgent = StrUtil.nullToDefault(request.getHeader("User-Agent"), "");
        if (StrUtil.isBlank(clientIp) && StrUtil.isBlank(userAgent)) {
            return null;
        }
        return "anonymous:" + SecureUtil.md5(clientIp + "|" + userAgent);
    }
}
