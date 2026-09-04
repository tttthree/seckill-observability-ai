package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.interceptor.UserHolder;
import static com.hmdp.constant.RedisConstants.SECKILL_ORDER_KEY;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @PostMapping("/seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 查询秒杀订单状态（给超时用户一个确认口）
     * GET /voucher-order/seckill/{voucherId}/status
     *
     * 返回三种状态：
     *   SUCCESS     — 订单已创建，返回订单详情
     *   PROCESSING  — Redis 已扣但 DB 还没写入，订单处理中
     *   NOT_FOUND   — 查不到任何下单记录
     */
    @GetMapping("/seckill/{voucherId}/status")
    public Result checkOrderStatus(@PathVariable Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 第一优先级：查 DB（最终真相）
        VoucherOrder order = voucherOrderService.lambdaQuery()
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherId)
                .one();

        if (order != null) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("order_id", order.getId());
            result.put("voucher_id", order.getVoucherId());
            result.put("create_time", order.getCreateTime());
            result.put("message", "订单已创建，恭喜抢购成功！");
            return Result.ok(result);
        }

        // 第二优先级：查 Redis Set（Lua 已成功，但还没到 DB）
        String orderKey = SECKILL_ORDER_KEY + voucherId;
        //isMember 查某个值在不在里面
        Boolean inSet = stringRedisTemplate.opsForSet()
                .isMember(orderKey, userId.toString());

        if (Boolean.TRUE.equals(inSet)) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "PROCESSING");
            result.put("message", "订单处理中，预计几秒内完成，请稍后刷新查看");
            return Result.ok(result);
        }

        // 第三优先级：没找到
        Map<String, Object> result = new HashMap<>();
        result.put("status", "NOT_FOUND");
        result.put("message", "未查到下单记录，很遗憾本次未抢到");
        return Result.ok(result);
    }
}