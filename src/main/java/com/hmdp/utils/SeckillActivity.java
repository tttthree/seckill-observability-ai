package com.hmdp.utils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.hmdp.constant.RedisConstants.*;

/** DB LocalDateTime 按应用时区转换；Redis 活动元数据均为 epoch millis。 */
public final class SeckillActivity {
    private SeckillActivity() { }

    public static Map<String, String> metadata(Long id, LocalDateTime begin, LocalDateTime end) {
        if (begin == null || end == null || begin.isAfter(end)) {
            throw new IllegalArgumentException("活动起止时间缺失或非法");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put(SECKILL_BEGIN_KEY + id, String.valueOf(begin.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        values.put(SECKILL_END_KEY + id, String.valueOf(end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        values.put(SECKILL_ACTIVE_KEY + id, "1");
        return values;
    }

    public static String status(String active, String begin, String end, long now) {
        try {
            if (active == null || begin == null || end == null) return "METADATA_MISSING";
            long first = Long.parseLong(begin), last = Long.parseLong(end);
            if (first > last) return "METADATA_MISSING";
            if (!"1".equals(active)) return "STOPPED";
            if (now < first) return "NOT_STARTED";
            if (now > last) return "ENDED";
            return "ACTIVE";
        } catch (NumberFormatException e) {
            return "METADATA_MISSING";
        }
    }
}
