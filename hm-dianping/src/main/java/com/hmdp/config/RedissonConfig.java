package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class RedissonConfig {
    @Value("${spring.redis.host:127.0.0.1}")
    private String redisHost;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        //配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + redisHost + ":6379").setUsername("root").setPassword("root");
        //创建RedissonClient对象
        return Redisson.create(config);
    }

    @Bean(destroyMethod = "shutdown")
    @Profile("redisson-cluster-test")
    public RedissonClient redissonClient2() {
        //配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6380");
        //创建RedissonClient对象
        return Redisson.create(config);
    }

    @Bean(destroyMethod = "shutdown")
    @Profile("redisson-cluster-test")
    public RedissonClient redissonClient3() {
        //配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6381");
        //创建RedissonClient对象
        return Redisson.create(config);
    }
}
