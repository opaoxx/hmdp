package com.hmdp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.nio.charset.StandardCharsets;

/**
 * Redis ACL configuration for Spring Boot 2.3.
 *
 * Spring Boot 2.3 and its bundled Spring Data Redis version do not support an
 * ACL username in their connection properties. The username is sent with a
 * raw AUTH command after the Lettuce connection is opened.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.redis.port:6379}")
    private int port;

    @Value("${spring.redis.username:}")
    private String username;

    @Value("${spring.redis.password:}")
    private String password;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        return new AclLettuceConnectionFactory(configuration, username, password);
    }

    private static class AclLettuceConnectionFactory extends LettuceConnectionFactory {

        private final String username;
        private final String password;

        private AclLettuceConnectionFactory(RedisStandaloneConfiguration configuration,
                                            String username,
                                            String password) {
            super(configuration);
            this.username = username;
            this.password = password;
        }

        @Override
        public RedisConnection getConnection() {
            RedisConnection connection = super.getConnection();
            if (username != null && !username.trim().isEmpty()
                    && password != null && !password.trim().isEmpty()) {
                connection.execute("AUTH",
                        username.getBytes(StandardCharsets.UTF_8),
                        password.getBytes(StandardCharsets.UTF_8));
            }
            return connection;
        }
    }
}
