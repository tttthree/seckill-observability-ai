-- 原子隔离失败消息：XACK 原消息 + XADD 到死信队列 + 删除重试计数。
-- 刻意不触碰库存 key 与一人一单资格 key：进入 DLQ 时无法证明此前的事务不会晚提交，
-- 自动回补库存或释放资格会在晚提交后造成重复释放。保留预占由"不修改这些 key"这一事实保证，
-- 因此不需要任何额外状态字段。
-- KEYS: 1 sourceStream, 2 deadLetterStream, 3 retryKey
-- ARGV: 1 group 消费者组, 2 messageId 消息 id, 3 userId 用户 id, 4 voucherId 券 id,
--       5 orderId 订单 id, 6 failureReason 失败原因
local acked = redis.call('xack', KEYS[1], ARGV[1], ARGV[2])
if acked == 0 then
    return 0
end

redis.call('xadd', KEYS[2], '*',
        'userId', ARGV[3],
        'voucherId', ARGV[4],
        'id', ARGV[5],
        'originalMessageId', ARGV[2],
        'failureReason', ARGV[6])
redis.call('del', KEYS[3])
return 1
