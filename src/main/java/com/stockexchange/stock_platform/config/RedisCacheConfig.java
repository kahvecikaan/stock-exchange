package com.stockexchange.stock_platform.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockexchange.stock_platform.dto.StockPriceDto;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class RedisCacheConfig {

    /**
     * Base Jackson mapper for Redis:
     *  - Handles Java 8 date/time types
     *  - Emits ISO-8601 strings (not numeric timestamps)
     */
    @Bean
    public ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule()); // supports LocalDateTime, ZonedDateTime
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * Generic JSON serializer for caching Lists (and arbitrary Objects).
     * Jackson “default typing” injects an `@class` property so on read-back
     * Jackson knows exactly which Java type to instantiate.
     */
    @Bean
    public GenericJackson2JsonRedisSerializer listSerializer(ObjectMapper base) {
        // Copy the base mapper, so we don't affect the global one
        ObjectMapper m = base.copy();
        m.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY // writes "@class":"com.example.StockPriceDto" in JSON
        );
        return new GenericJackson2JsonRedisSerializer(m);
    }

    /**
     * Typed JSON serializer for a single StockPriceDto.
     * No extra type metadata is needed—Jackson knows the target class.
     */
    @Bean
    public Jackson2JsonRedisSerializer<StockPriceDto> dtoSerializer(ObjectMapper mapper) {
        return new Jackson2JsonRedisSerializer<>(mapper, StockPriceDto.class);
    }

    /**
     * Build the default Redis cache configuration:
     *  - Don’t store nulls
     *  - Use String keys (human-readable)
     *  - Use the listSerializer for values by default
     */
    @Bean
    public RedisCacheConfiguration defaultCacheConfig(GenericJackson2JsonRedisSerializer listSerializer) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                // Key serialization: Java String → UTF-8 bytes
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                // Value serialization: Java Object → bytes via listSerializer.serialize(...)
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(listSerializer));
    }

    /**
     * Assemble the CacheManager:
     * 1) .cacheDefaults(...) sets up the defaultConfig—including listSerializer
     * 2) .withInitialCacheConfigurations(...) lets us override TTLs and swap in
     *    the dtoSerializer for the single-object cache.
     */
    @Bean
    public CacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            RedisCacheConfiguration defaultCacheConfig,
            Jackson2JsonRedisSerializer<StockPriceDto> dtoSerializer
    ) {
        Map<String, RedisCacheConfiguration> configs = new HashMap<>();

        // ─── List caches (all use listSerializer, but each has its own TTL) ─────────
        configs.put("stockPrices_1d", defaultCacheConfig.entryTtl(Duration.ofMinutes(2)));
        configs.put("stockPrices_1w", defaultCacheConfig.entryTtl(Duration.ofMinutes(5)));
        configs.put("stockPrices_1m", defaultCacheConfig.entryTtl(Duration.ofMinutes(15)));
        configs.put("stockPrices_3m", defaultCacheConfig.entryTtl(Duration.ofMinutes(30)));
        configs.put("stockPrices_1y", defaultCacheConfig.entryTtl(Duration.ofHours(1)));
        configs.put("stockPrices_5y", defaultCacheConfig.entryTtl(Duration.ofHours(2)));

        // ─── Single-object cache (currentPrices) ─────────────────────────────────────
        // override the value-serializer to use dtoSerializer (plain JSON), TTL=30s
        RedisCacheConfiguration singleDtoConfig = defaultCacheConfig
                // now Java StockPriceDto → bytes via dtoSerializer.serialize(...)
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(dtoSerializer))
                .entryTtl(Duration.ofSeconds(30));
        configs.put("currentPrices", singleDtoConfig);

        // Build the manager:
        //  - cacheDefaults() applies defaultCacheConfig to any cache not in 'configs'
        //  - withInitialCacheConfigurations(configs) applies our per-cache overrides
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultCacheConfig)       // JSON for everything by default
                .withInitialCacheConfigurations(configs) // TTLs + override serializer for currentPrices
                .transactionAware()
                .build();
    }
}
