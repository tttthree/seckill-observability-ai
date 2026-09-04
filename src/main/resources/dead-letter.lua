-- 原子地终结一条失败的订单消息：ACK 确认、回补预占、追加死信记录。
-- KEYS: sourceStream 源流, stockKey 库存 key, orderedUsersKey 已下单用户 key, deadLetterStream 死信流, retryKey 重试计数 key
-- ARGV: group 消费者组, messageId 消息 id, userId 用户 id, voucherId 券 id, orderId 订单 id, failureReason 失败原因
local acked = redis.call('xack', KEYS[1], ARGV[1], ARGV[2])
if acked == 0 then
    return 0
end

redis.call('incrby', KEYS[2], 1)
redis.call('srem', KEYS[3], ARGV[3])
redis.call('xadd', KEYS[4], '*',
        'userId', ARGV[3],
        'voucherId', ARGV[4],
        'id', ARGV[5],
        'originalMessageId', ARGV[2],
        'failureReason', ARGV[6])
redis.call('del', KEYS[5])
return 1
