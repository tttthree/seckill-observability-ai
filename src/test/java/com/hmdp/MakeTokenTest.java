package com.hmdp;

import cn.hutool.core.util.RandomUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.hmdp.constant.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * @author zt
 * @version 1.0
 */
@SpringBootTest
public class MakeTokenTest {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

   @Test
    public void generateTokensForSeckillTest() throws Exception {

        String filePath = "D:/tokens.txt";
        List<String> tokens = new ArrayList<>();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            for (int i = 1; i <= 2000; i++) {

                String token = UUID.randomUUID().toString().replace("-", "");
                tokens.add(token);

                writer.write(token);
                writer.newLine();
            }
        }

        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {

            for (int i = 0; i < tokens.size(); i++) {

                String token = tokens.get(i);
                String key = "login:token:" + token;

                Map<String, String> userMap = new HashMap<>();
                userMap.put("id", String.valueOf(i + 1));
                userMap.put("nickName", USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

                //转换成 byte[]，如果不用connection就不用转，直接用opsForHash
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

                //遍历map里每一对的键值对，取出k和v进行hset从map写入到redis的hash里
                userMap.forEach((k, v) -> {
                    connection.hashCommands().hSet(
                            keyBytes,
                            k.getBytes(StandardCharsets.UTF_8),
                            v.getBytes(StandardCharsets.UTF_8)
                    );
                });

                //设置过期时间为300min
                connection.keyCommands().expire(
                        keyBytes,
                        86400
                );
            }

            return null;
        });

        System.out.println("🔥 Hash结构Token生成完成！");
    }
}
