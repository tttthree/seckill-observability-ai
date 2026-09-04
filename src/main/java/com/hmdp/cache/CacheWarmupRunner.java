package com.hmdp.cache;

import com.hmdp.constant.RedisConstants;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constant.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.constant.RedisConstants.CACHE_SHOP_TTL;

/**
 * 启动时全量预热店铺到 Redis，避免冷启动缓存击穿。
 *
 * @author zt
 * @version 1.0
 */
@Slf4j
@Component
// Spring Boot 启动流程：容器初始化完成 → 找到所有 ApplicationRunner → 逐个调用 run()
public class CacheWarmupRunner implements ApplicationRunner {

    @Resource
    private IShopService shopService;

    @Resource
    private CacheClient cacheClient;

    @Override
    public void run(ApplicationArguments args) {
        log.info("开始预热店铺缓存...");
        try {
            //MyBatis-Plus 全表查 DB
            List<Shop> shops = shopService.list();
            if (shops == null || shops.isEmpty()) {
                log.info("无店铺数据需要预热");
                return;
            }
            int count = 0;
            for (Shop shop : shops) {
                try {
                    // 逻辑过期 30 分钟 + 互斥锁异步重建
                    cacheClient.setWithLogicalExpire(
                            CACHE_SHOP_KEY + shop.getId(),
                            shop,
                            CACHE_SHOP_TTL,
                            TimeUnit.MINUTES
                    );
                    count++;
                } catch (Exception e) {
                    log.warn("预热店铺缓存失败 shopId={}", shop.getId(), e);
                }
            }
            log.info("店铺缓存预热完成: {} 家店铺已写入 Redis", count);
        } catch (Exception e) {
            log.error("店铺缓存预热异常", e);
        }
    }
}
