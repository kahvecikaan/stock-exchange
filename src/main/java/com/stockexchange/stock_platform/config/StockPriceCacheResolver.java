package com.stockexchange.stock_platform.config;

import lombok.NonNull;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Collection;

@Component("stockPriceCacheResolver")
public class StockPriceCacheResolver implements CacheResolver {

    private final CacheManager cacheManager;

    public StockPriceCacheResolver(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * Chooses exactly one cache based on the 'timeframe' argument.
     */
    @Override
    @NonNull
    public Collection<? extends Cache> resolveCaches(CacheOperationInvocationContext<?> context) {
        // arguments: 0=symbol, 1=timeframe, 2=userTimezone
        Object[] args = context.getArgs();
        String timeframe = args.length > 1 && args[1] != null
                ? args[1].toString().toLowerCase()
                : "1d";
        String cacheName = "stockPrices_" + timeframe;
        Cache cache = cacheManager.getCache(cacheName);

        return Collections.singleton(cache);
    }
}
