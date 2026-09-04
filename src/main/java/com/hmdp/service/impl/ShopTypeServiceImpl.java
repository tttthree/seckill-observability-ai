package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constant.RedisConstants.*;

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
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getTypeList() {
        String typeKey = CACHE_TYPE_KEY;

        //1.判断是否存在缓存
            //1.1.从redis查询缓存长度
            //获取列表全部元素
            List<String> typeJsonList = stringRedisTemplate.opsForList().range(typeKey, 0, -1);
            //1.2.存在，直接返回
            if (typeJsonList != null && !typeJsonList.isEmpty()) {
                //创建list存放转换后的对象
                List<ShopType> shopTypeList = new ArrayList<>();
                for (String typeJson : typeJsonList) {
                    //将typeJsonList里的JSON对象遍历出来转成ShopType，然后封装到shopTypeList
                    shopTypeList.add(JSONUtil.toBean(typeJson, ShopType.class));
                }
                return Result.ok(shopTypeList);
            }
        //2.不存在，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
            //2.1.不存在，返回错误
            if (typeList == null || typeList.isEmpty()) {
                // 缓存空值，防缓存穿透
                stringRedisTemplate.opsForList().rightPush(typeKey, "[]");
                stringRedisTemplate.expire(typeKey, CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.ok(Collections.emptyList());
            }
            //2.2.存在，写入redis
            List<String> jsonList = new ArrayList<>();
            for (ShopType type : typeList) {
                jsonList.add(JSONUtil.toJsonStr(type));
            }
            stringRedisTemplate.opsForList().rightPushAll(typeKey,jsonList);
        //3.设置缓存过期时间
        stringRedisTemplate.expire(typeKey, CACHE_TYPE_TTL, TimeUnit.HOURS);
        //4.返回
        return Result.ok(typeList);
    }
}
