package com.beingadish.AroundU.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
@Profile("!test")
public class RedisConfig {

    public static final String CACHE_JOB_DETAIL = "job:detail";
    public static final String CACHE_CLIENT_JOBS = "job:client:list";
    public static final String CACHE_WORKER_FEED = "job:worker:feed";
    public static final String CACHE_USER_PROFILE = "user:profile";
    public static final String CACHE_WORKER_SKILLS = "worker:skills";

    /**
     * Single shared cache serializer. Built once and reused across every cache
     * configuration and the RedisTemplate, instead of rebuilding an ObjectMapper
     * per cache.
     */
    private final GenericJackson2JsonRedisSerializer redisSerializer = buildRedisSerializer();

    // Create properly configured serializer
    private static GenericJackson2JsonRedisSerializer buildRedisSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Restrict polymorphic deserialization to our own types plus JDK
        // collections and java.time, rather than the permissive default
        // validator. Closes the default-typing deserialization-gadget risk while
        // still round-tripping every cached DTO (all under
        // com.beingadish.AroundU.* / java.*). The serialized format is unchanged,
        // so pre-existing cache entries still deserialize.
        BasicPolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.beingadish.AroundU.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .build();

        mapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL);

        return new GenericJackson2JsonRedisSerializer(mapper);
    }

    private RedisCacheConfiguration baseConfig(Duration ttl) {
        return RedisCacheConfiguration
                .defaultCacheConfig()
                .entryTtl(ttl)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(redisSerializer)
                )
                .disableCachingNullValues();
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        cacheConfigs.put(CACHE_JOB_DETAIL, baseConfig(Duration.ofMinutes(30)));
        cacheConfigs.put(CACHE_CLIENT_JOBS, baseConfig(Duration.ofMinutes(10)));
        cacheConfigs.put(CACHE_WORKER_FEED, baseConfig(Duration.ofMinutes(5)));
        cacheConfigs.put(CACHE_USER_PROFILE, baseConfig(Duration.ofHours(1)));
        cacheConfigs.put(CACHE_WORKER_SKILLS, baseConfig(Duration.ofHours(6)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(baseConfig(Duration.ofMinutes(10)))
                .withInitialCacheConfigurations(cacheConfigs)
                .enableStatistics()
                .build();
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {

        GenericJackson2JsonRedisSerializer serializer = redisSerializer;

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();

        return template;
    }
}